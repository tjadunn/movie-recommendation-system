package api_server.movieRecommender

import cats.effect._
import com.comcast.ip4s._

import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.ember.server._
import org.http4s.circe._

import io.circe.generic.auto._
import io.circe.syntax._

import java.nio.file.{Files, Paths}

import io.circe.parser.decode

// Define a movie recommendation type that we can deserialise into
final case class Movie(id: Int, recommended:List[Int], relevance: List[Int])

// Define a simple database & some commands we want to perform
trait movieDatabase[A] {
  // This API is only concerned with GET requests
  def get_movie(movie_id: Int, datastore: A): IO[Option[Movie]]
}

/* Define a class to implement movieDatabase methods on our datastore
* The example given is pretty small so we can just do this in memory - larger scale we'd want to have
* it stored in a something like Redis for quick key:value lookup
*/
class movieDatabaseKeyValueStore extends movieDatabase[String] {

  // To make this easily testable pull out the reader and define source on it which we can patch in later
  def get_json(source: scala.io.Source): IO[List[Movie]] = {
    IO(source.getLines()).flatMap {
        contents => decode[List[Movie]](contents.mkString) match {
          case Right(movies)  => IO.pure(movies)
          case Left(error) => IO.raiseError(new Exception(error.getMessage))// raise a server error, unable to decode JSON
        }
    }
  }

  override def get_movie(movie_id: Int, datastore: String): IO[Option[Movie]] = {
    get_json(scala.io.Source.fromFile(datastore)).flatMap {
      m => IO(m.find(m => m.id == movie_id))
    }
  }
}

class movieRecommenderApp(database: movieDatabase[String]) extends IOApp {
   val movieService = HttpRoutes.of[IO] {
    // Define the url patterns for GET
    // We can apply a flatMap pattern to get the movie as IO is a monad
    case GET -> Root / IntVar(movieId) => database.get_movie(movieId, "db.json").flatMap({
      case Some(movie: Movie) => Ok(movie.asJson) // circe generic auto can implicitly derive an enconder for us here
      case None => NotFound(s"No movie with movie id $movieId found")
   })
  }.orNotFound

  def run(args: List[String]): IO[ExitCode] = {
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(movieService)
      .build
      .use(_ => IO.never)
      .as(ExitCode.Success)
    }
}

object Main extends IOApp{
   val movie_db = new movieDatabaseKeyValueStore()
   // Run a simple server
  override def run(args: List[String]): IO[ExitCode] = {
    val movieRecommenderApp = new movieRecommenderApp(movie_db)
    movieRecommenderApp.run(args)
  }
}

