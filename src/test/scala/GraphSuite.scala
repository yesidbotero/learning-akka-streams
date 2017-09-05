import org.scalatest.FunSuite
import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ClosedShape, UniformFanInShape, UniformFanOutShape}
import akka.stream.scaladsl._
import org.scalatest.FunSuite

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
    assertCompiles("graph.run")

  }


}
