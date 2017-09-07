import org.scalatest.FunSuite
import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ClosedShape, UniformFanInShape, UniformFanOutShape}
import akka.stream.scaladsl._
import org.scalatest.FunSuite

import scala.concurrent.Future

class GraphSuite extends FunSuite {

  test("creating a Graph") {
    implicit val actorSystem = ActorSystem("system")
    implicit val materializer = ActorMaterializer()

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
  test("two parallels streams"){
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val actorSystem = ActorSystem("system")
    implicit val materializer = ActorMaterializer()


    val source = Source(1 to 3)
    val sink1: Sink[Int, Future[Int]] = Sink.fold(0)(_ + _)
    val sink2: Sink[Int, Future[Int]] = Sink.reduce((previous: Int, input: Int) => previous + input)
    def combineMat = (mat1: Future[Int], mat2: Future[Int]) => mat1.flatMap(m => mat2.map(_ + m))
    val flow: Flow[Int, Int, NotUsed] = Flow[Int].map((x:Int) => x * x)
    val grph = RunnableGraph.fromGraph(GraphDSL.create(sink1, sink2)(combineMat){
      implicit builder => (sk1, sk2) =>
        import GraphDSL.Implicits._
        val broadcast = builder.add(Broadcast[Int](2))
        source ~> broadcast.in
        broadcast.out(0) ~> flow ~> sk1
        broadcast.out(1) ~> flow ~> sk2
        ClosedShape
    })
    grph.run().map(println)
    assert(true)
  }
}
