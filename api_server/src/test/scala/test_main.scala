package test

import cats.implicits._
import cats.effect.unsafe.implicits.global // we need this to run unsafeRunSync
import cats.effect._

import org.http4s._
import org.http4s.circe._

import io.circe._, io.circe.parser._

import org.scalatest.funsuite.AnyFunSuite
import org.scalamock.scalatest.MockFactory

import api_server.movieRecommender.{Movie, movieDatabaseKeyValueStore, movieRecommenderApp}


// Test the API layer
class apiLayerTest extends AnyFunSuite with MockFactory{

  // define a parameterized check method so we can handle the different response types - modified from http4s docs
  def check[A](actual: IO[Response[IO]], expected_status: Status, expected_body: Option[A])(
      implicit ev: EntityDecoder[IO, A] //circe string & json decoders will resolve this for us
  ): Boolean = {
     val actualResp = actual.unsafeRunSync
     val statusCheck = actualResp.status == expected_status 
     val bodyCheck = expected_body.fold[Boolean](
         actualResp.body.compile.toVector.unsafeRunSync.isEmpty)( // Verify Response's body is empty.
         expected => actualResp.as[A].unsafeRunSync == expected
     )
    //println(actual.unsafeRunSync().as[A].unsafeRunSync())
     statusCheck && bodyCheck
  }

  test("Check the API retutns a JSON of a movie which exists in our 'DB'") {
    // We expect to get recommendations for movie id: 1
    val expected_json: Json = parse(
      """{"id":1, "recommended":[58,105], "relevance":[1,2]}"""
    ).getOrElse(Json.Null)

    // Create a mock data store 
    val mock_key_value_store = stub[movieDatabaseKeyValueStore]

    // When we call our datastore with id 1 make it return Some(Movie(1,...
    (mock_key_value_store.get_movie _).when(1, *).returns(
      IO(Some(Movie(1, List(58, 105), List(1, 2))))
    )

    // Inject our mock datastore into the app
    val app = new movieRecommenderApp(mock_key_value_store)

    // A good request
    val response: IO[Response[IO]] = app.movieService.run(
      Request(method = Method.GET, uri = Uri.uri("/1"))
    )

    // Perform the check against the json
    assert(check[Json](
      response,
      Status.Ok,
      Some(expected_json)
      )
    )
  }

  test("Movie doesn't exist in the db") {
    // Create our mock data store make it return None for an id of 3000
    val mock_key_value_store = stub[movieDatabaseKeyValueStore]
    (mock_key_value_store.get_movie _).when(3000, *).returns(
      IO(None)
    )
    // Inject our datastore into the app
    val app = new movieRecommenderApp(mock_key_value_store)
    // A Good request but with no movie in our datastore 
    val bad_request_response: IO[Response[IO]] = app.movieService.run(
      Request(method = Method.GET, uri = Uri.uri("/3000"))
    )
    assert(check[String](
      bad_request_response,
      Status.NotFound,
      Some("No movie with movie id 3000 found")
      )
    )
  }

  test("Bad response - undefined endpoint") {
    // Create our mock data store 
    val mock_key_value_store = stub[movieDatabaseKeyValueStore]
    // Inject our datastore into the app
    val app = new movieRecommenderApp(mock_key_value_store)
    // A bad request :(
    val bad_request_response: IO[Response[IO]] = app.movieService.run(
      Request(method = Method.GET, uri = Uri.uri("/badrequest"))
    )
    assert(check[String](
      bad_request_response,
      Status.NotFound,
      Some("Not found")
      )
    )
  }
}

// Test the data layer
class dataLayerTest extends AnyFunSuite with MockFactory {

  test("Data layer correctly deserialises json array into Movie case classes") {
    val movie_database = new movieDatabaseKeyValueStore()

    // Patch in a source
    val source: scala.io.Source = scala.io.Source.fromString(
      """[{"id":"1","recommended":["58","105"],"relevance":[1,2]},{"id":96,"recommended":[70,6],"relevance":[1,2]}]"""
    )
    // Run our 'db' against this source
    val lines: IO[List[Movie]] = movie_database.get_json(source)

    val expected_result: List[Movie] = List(
      Movie(1, List(58, 105), List(1, 2)),
      Movie(96, List(70, 6), List(1, 2))
    )
    assert(lines.unsafeRunSync() == expected_result)
  }
}
