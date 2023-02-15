package sparkRecommender

import org.apache.spark.sql.functions
import org.apache.spark.ml.feature.StopWordsRemover
import org.apache.spark.ml.feature.{HashingTF, IDF, Tokenizer}

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.{row_number, col, split}
import org.apache.spark.ml.feature.VectorAssembler

import org.apache.spark.ml.linalg.{Vector => MLVector, Vectors => MLVectors}
import java.io._

object Recommender {
  /* A basic recommendation algorithm using euclidean distance similarity
   *
   * Speedups could be achieved by using probablistic data structures -> reducing shuffling in the join
   * Please see README for design decisions and further explanations
   */

  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder
      .appName("Movie recommendation generator")
      .config("spark.master", "local")
      // The dataframe we are creating is tiny we don't need the 200 partitions that Spark defaults to 
      // We want to set it to 1 in tests and in small examples like this else Spark creates too much task overhead

      // This would need to be scaled to at least the number of cores on the machine in production
      // Most likely much more (total data size / 128mb) ~= # partitions
      .config("spark.sql.shuffle.partitions", "1") 
      .getOrCreate()

    spark.sparkContext.setLogLevel("ERROR") // spark log level is verbose

    // This is  abit hacky - we could specify the schema properly to stop us having to drop the corrupt record column
    // in the interest of time this has not been done
    val movie_data = spark.read.json(
      "spark_recommender_job/src/main/resources/metadatas.json"
    ).drop("_corrupt_record").na.drop

    val split_title_data = movie_data.withColumn(
      "title", (split(col("title")," ").as("title"))
    ).withColumn("length", functions.array(col("length")))

    // Create a StopWordsRemover to remove stop words which have no imact ("the", "and"... etc)
    val remover = new StopWordsRemover().setInputCol("title").setOutputCol(
      "filtered_title"
    )
    val movies_without_stop_words  = remover.transform(split_title_data)

    // For each column we want to hash the vector to represent a point in euclidean space
    val hashing_tf = new HashingTF().setInputCol("tags").setOutputCol("tags_features")
    val hashed_genre_tags= hashing_tf.transform(movies_without_stop_words)

    // Hash the movie title
    val hashing_tf_title = new HashingTF().setInputCol("title").setOutputCol(
      "title_features"
    )
    val hashed_titles = hashing_tf_title.transform(hashed_genre_tags)

    // Hash its duration
    val hashing_tf_length = new HashingTF().setInputCol("length").setOutputCol(
      "length_features"
    )
    val hashed_all_features = hashing_tf_length.transform(hashed_titles)

    // We can use VectorAssembler to form 1 data point (features column) by combining the previously formed 
    // hash features e.g. tags_features, title_features ... -> col(features)
    val assembler = new VectorAssembler().setInputCols(
      Array("tags_features", "title_features", "length_features")
    ).setOutputCol("features")

    // Final assembled DataFrame with our features columns
    val final_df = assembler.transform(hashed_all_features)

    //    Top 5 columns may look like this
    //    +---+------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+
    //    | id|length|                tags|               title|      filtered_title|       tags_features|      title_features|     length_features|            features|
    //    +---+------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+
    //    |  1| [142]|[Crime, Drama, Pr...|[The, Shawshank, ...|[Shawshank, Redem...|(262144,[44954,51...|(262144,[108919,1...|(262144,[70265],[...|(786432,[44954,51...|
    //    |  2| [175]|[Crime, Drama, Cr...|    [The, Godfather]|         [Godfather]|(262144,[51515,10...|(262144,[136656,2...|(262144,[202321],...|(786432,[51515,10...|
    //    |  3| [142]|[Comedy, Drama, R...|     [Forrest, Gump]|     [Forrest, Gump]|(262144,[4915,160...|(262144,[50482,15...|(262144,[70265],[...|(786432,[4915,160...|
    //    |  4| [108]|[Crime, Drama, Th...|[On, the, Waterfr...|        [Waterfront]|(262144,[1158,515...|(262144,[95889,14...|(262144,[96853],[...|(786432,[1158,515...|
    //    |  5| [129]|     [Comedy, Drama]|[Mr., Smith, Goes...|[Mr., Smith, Goes...|(262144,[4915,209...|(262144,[6416,275...|(262144,[171873],...|(786432,[4915,209...|
    //    +---+------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+--------------------+
    //

    // We now want to find which of these feature vectors are closest to each other

    // Create two identical datasets with different refernce columns
    val dataset_a = final_df.select(
      col("id").alias("idA"), col("features").alias("featuresA")
    )
    val dataset_b = final_df.select(
      col("id").alias("idB"), col("features").alias("featuresB")
    )

    // Define a Euclidean distance function
    val dist_func = functions.udf(MLVectors.sqdist _)

    // Apply this over a cross join of each dataset, this will provide us with each vectors distance to every other
    //
    // Note - This is an n^2 problem but given a decent sized cluster and low requirements on run time we can get a result in a reasonable time even for large inputs
    // from experience 6 million records we can compute in around 2.5 hours given a 20 node cluster
    val cross_join_df = dataset_a.crossJoin(dataset_b).where(
      col("idA") =!= col("idB")
    ).withColumn("distances", dist_func(col("featuresA"), col("featuresB")))

    // Now we want to find the top closest vectors for eah datapoint - this lends itself to a window function
    val windowspec = Window.partitionBy("idA").orderBy("distances")

    // Calculate the row number for each distance then filter and get the top 3 recs
    val top_three = cross_join_df.withColumn(
      "row_num", row_number.over(windowspec)
    ).select(col("idA"), col("idB"), col("row_num")).where(col("row_num") < 4)

    // Write to a json file for the API to grab
    //
    // Admittedly these next few lines are a bit of a hack - it's fine for this example and you'd atually want to enable this in type of behaviour tests
    // But we're basically forcing Sparks hand to create one file nuking any paralellism (as we have shuffle partitions 1 -> we'll always get 1 output file anyway)
    // In a large scale example we'd never be using a single file as a persistent / in memory datastore so it's all kind of irrelevant anyway...
    val output_str = top_three.groupBy(
      col("idA").alias("id")
    ).agg(
      functions.collect_list(col("idB")).alias("recommended"),
      functions.collect_list(col("row_num")).alias("relevance")
    ).toJSON.collect.mkString("[", "," , "]")


    val file = new File("db.json")
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(output_str)
    bw.close()
  }
}
