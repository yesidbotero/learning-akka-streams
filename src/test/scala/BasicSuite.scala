import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Future

class BasicSuite extends FlatSpec with Matchers {
   implicit val actorSystem = ActorSystem("system")
   implicit val materializer = ActorMaterializer()

   "A Source " should "emit elements on demand" in {
      val source: Source[Int, NotUsed] = Source(1 to 10)
      source.runForeach(println)
   }

   it should "connect to a sink" in {
      val sink: Sink[Int, Future[Done]] = Sink.foreach(x => println("receiving: " + x))
      val source: Source[Int, NotUsed] = Source(10 to 20)
      source.runWith(sink)
   }

   it should "connect to a flow" in {
     val source: Source[Int, NotUsed] = Source(1 to 20)
     val sink: Sink[Int, Future[Done]] = Sink.foreach(x => println("receiving: " + x))
     val flow: Flow[Int, Int, NotUsed] = Flow[Int].filter(x => x % 2 == 0)
      //para pegarle un flow a un source y generar una fuente datos se hace con el método via

     //Una fuente por un flujo a un sumidero
     val graph = source via flow to sink
     graph.run()
   }

    it should "connect to a flow 2" in {
      val source = Source(10 to 20)
      val y: (Int) => String = (n: Int) => if (n % 2 == 0) "par" else "impar"

    val flow = Flow[Int].map{ y }

      val x: PartialFunction[Int, String] = { case n if n % 2 == 0 => "Par"
      case _ => "Impar"}

      val z: PartialFunction[Int, String] = { case n if n % 2 == 0 => "Par"}


    source.via(flow).runForeach(println)
  }
}
