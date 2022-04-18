package lichess

import akka.actor.ActorSystem
import akka.stream.*
import akka.stream.scaladsl.*
import akka.util.ByteString
import chess.format.FEN
import chess.format.pgn.Pgn
import chess.variant.{ Horde, Standard, Variant }
import com.typesafe.config.ConfigFactory
import java.nio.file.Paths
import lila.db.dsl.*
import lila.game.BSONHandlers.*
import lila.game.BSONHandlers.*
import lila.game.{ Game, PgnDump, Source as S }
import org.joda.time.DateTime
import reactivemongo.akkastream.{ cursorProducer, State }
import reactivemongo.api.*
import reactivemongo.api.bson.*
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class PuzzleLine(
    id: String,
    fen: FEN,
    moves: List[String],
    rating: Int,
    ratingDev: Int,
    popularity: Int,
    plays: Int,
    themes: List[String],
    gameUrl: String
)

@main def buildPuzzleDb(args: String*) =

  val path = args.headOption.getOrElse("out/lichess_db_puzzle.csv")

  println(s"Exporting to $path")

  val config   = ConfigFactory.load()
  val dbName   = "puzzler"
  val collName = "puzzle2_puzzle"

  val uri    = config.getString("db.puzzle.uri")
  val driver = new AsyncDriver(Some(config.getConfig("mongo-async-driver")))

  given system: ActorSystem = ActorSystem()
  given materializer: Materializer = ActorMaterializer(
    ActorMaterializerSettings(system)
      .withInputBuffer(
        initialSize = 32,
        maxSize = 32
      )
  )

  def parseDoc(doc: Bdoc): Option[PuzzleLine] = for {
    id         <- doc.string("_id")
    fen        <- doc.string("fen").map(FEN.apply)
    moves      <- doc.string("line")
    glicko     <- doc.child("glicko")
    rating     <- glicko.double("r")
    rd         <- glicko.double("d")
    popularity <- doc.double("vote")
    plays      <- doc.int("plays")
    themes     <- doc.getAsOpt[List[String]]("themes")
    gameId     <- doc.string("gameId")
  } yield PuzzleLine(
    id = id,
    fen = fen,
    moves = moves.split(' ').toList,
    rating = rating.toInt,
    ratingDev = rd.toInt,
    popularity = math.round(popularity * 100).toInt,
    plays = plays,
    themes = themes,
    gameUrl = {
      val asWhite = fen.color.contains(chess.White)
      val ply = fen.fullMove.fold(0) { fm =>
        fm * 2 - fen.color.fold(0)(_.fold(1, 0))
      }
      s"https://lichess.org/${gameId}${if (asWhite) "" else "/black"}#${ply}"
    }
  )

  def toCsvLine(puzzle: PuzzleLine): String =
    List(
      puzzle.id,
      puzzle.fen.value,
      puzzle.moves.mkString(" "),
      puzzle.rating,
      puzzle.ratingDev,
      puzzle.popularity,
      puzzle.plays,
      puzzle.themes.sorted.mkString(" "),
      puzzle.gameUrl
    ).mkString(",")

  def csvSink: Sink[String, Future[IOResult]] =
    Flow[String]
      .map { line =>
        ByteString(s"$line\n")
      }
      .toMat(FileIO.toPath(Paths.get(path)))(Keep.right)

  MongoConnection
    .fromString(uri)
    .flatMap { parsedUri =>
      driver.connect(parsedUri, Some("lichess-puzzle"))
    }
    .flatMap(_.database(dbName))
    .flatMap {
      _.collection(collName)
        .find(BSONDocument("issue" -> BSONDocument("$exists" -> false)))
        .sort(BSONDocument("_id" -> 1))
        .cursor[Bdoc]()
        // .cursor[Bdoc](readPreference = ReadPreference.secondary)
        .documentSource(
          // maxDocs = 10,
          maxDocs = Int.MaxValue,
          err = Cursor.ContOnError((_, e) => println(e.getMessage))
        )
        .buffer(1000, OverflowStrategy.backpressure)
        .mapConcat(d => parseDoc(d).toList)
        .map(toCsvLine)
        .runWith(csvSink) andThen { _ =>
        driver.close()
        system.terminate()
      }
    }
