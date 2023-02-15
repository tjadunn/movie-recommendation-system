# Spark can be pretty picky about versions of Java/Scala etc this is to circumvent that
FROM sbtscala/scala-sbt:graalvm-ce-21.2.0-java8_1.8.1_2.12.17

WORKDIR /rec_project

# Copy the codebase
ADD . /rec_project

# Build our recommendations
CMD sbt build_recs
