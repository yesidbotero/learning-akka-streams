import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.{Done, NotUsed}
import org.scalatest.FunSuite

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class BasicSuite extends FunSuite {
  implicit val actorSystem = ActorSystem("ActorsSystem")
  implicit val materializer = ActorMaterializer()

  test("A Source emits elements on demand") {
    val source: Source[Int, NotUsed] = Source(1 to 10)

    assertCompiles("source.runForeach(println)")
  }

  test("A Source should be able to run privideing it a sink") {
    val sink: Sink[Int, Future[Done]] = Sink.foreach(x => println("receiving: " + x))
    val source: Source[Int, NotUsed] = Source(10 to 20)

    assertCompiles("source.runWith(sink)")
  }

  test("A Source connects to a flow") {
    val source: Source[Int, NotUsed] = Source(1 to 3)
    val isPairFlow: Flow[Int, String, NotUsed] = Flow[Int].map { case n if n % 2 == 0 => "Par "
    case _ => "Impar "
    }
    val sink: Sink[String, Future[String]] = Sink.fold("")(_ + _)
    val result: Future[String] = source.via(isPairFlow).runWith(sink)

    assert(Await.result(result, Duration.Inf) == "Impar Par Impar ")
  }

  test("A Sink can materialize using 'toMat' method") {
    // when a source connects to a sink using the 'to' method, it doesn't return the defined materialized value for the sink
    val source = Source(List(1, 2, 3)).map(_ * 2)
    val sink: Sink[Int, Future[Int]] = Sink.fold(0)(_ + _)
    val graph: RunnableGraph[Future[Int]] = source.toMat(sink)(Keep.right)
    //the method runWith connects a Source to a Sink a run it, returning the sink's materialized value
    //the blueprint concept
    assert(Await.result(graph.run, Duration.Inf) == Await.result(source.runWith(sink), Duration.Inf))
  }

  test("working with lists") {
    val source: Source[List[Int], NotUsed] = Source.fromFuture(Future(List(1, 2, 3, 4, 5)))
    val flow: Flow[List[Int], List[Int], NotUsed] = Flow[List[Int]].map(x => x.map(y => y + 1))
    val sink = Sink.foreach(println)
    val graph = source via flow to sink
    assertCompiles("graph.run")
  }

}
