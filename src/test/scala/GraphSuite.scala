import org.scalatest.FunSuite
import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ClosedShape, UniformFanInShape, UniformFanOutShape}
import akka.stream.scaladsl._
import org.scalatest.FunSuite

import scala.concurrent.Future

class GraphSuite extends FunSuite {
  implicit val actorSystem = ActorSystem("system")
  implicit val materializer = ActorMaterializer()

  test("creating a Graph") {
    val graph: RunnableGraph[NotUsed] = RunnableGraph.fromGraph(GraphDSL.create() {
      implicit builder =>
        //GraphDSL.Implicits._ brings into scope ~> Operator, read as via, to o edge
        import GraphDSL.Implicits._
        val source = Source(1 to 10)
        val sink = Sink.foreach(println)

        //Building a broadcast using the builder
        val broadcast: UniformFanOutShape[Int, Int] = builder.add(Broadcast[Int](2))
        //Building a merge using th builder
        val merge: UniformFanInShape[Int, Int] = builder.add(Merge[Int](2))
        //Defineing the flows
        val flowAfterSource = Flow[Int].map(_ * 2)
        val flow1AfterBcast = Flow[Int].map(_ + 1)
        val flow2AfterBcast = Flow[Int].map(_ - 1)

        //the implicit builder is used implicitly by the operator ~>
        source ~> flowAfterSource ~> broadcast ~> flow1AfterBcast ~> merge ~> sink
        broadcast ~> flow2AfterBcast ~> merge
        ClosedShape
    })
    assert(true)
  }

  //remember: read GraphDSL.create implementation
  test("defineing two parallels streams"){
    //sink that adds all streams elements
    val sink1: Sink[Int, Future[Int]] = Sink.fold(0)(_ + _)
    val sink2: Sink[Int, Future[Int]] = Sink.fold(0)(_ * _)
    //Source produces ten elements
    val source = Source(1 to 10)
    //Flows that applies the f function
    def f = (x:Int) => x * x
    val flow: Flow[Int, Int, NotUsed] = Flow[Int].map(f)
    val grph = RunnableGraph.fromGraph(GraphDSL.create(sink1, sink2)((_, _)){
      implicit builder => (sk1, sk2) =>
        import GraphDSL.Implicits._
        val broadcast = builder.add(Broadcast[Int](2))
        Source.single(1) ~> broadcast.in

        broadcast.out(0) ~> flow ~> sk1
        broadcast.out(1) ~> flow ~> sk2
        ClosedShape
    })
    assert(true)
  }




}
