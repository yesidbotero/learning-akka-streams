import java.nio.ByteOrder

import org.scalatest.FunSuite
import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.actor.Status.Success
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import org.scalatest.FunSuite
import org.scalatest.time.Millis

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.collection.parallel.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

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
  test("two parallel streams") {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val actorSystem = ActorSystem("system")
    implicit val materializer = ActorMaterializer()

    val source = Source(1 to 3)

    val sink1: Sink[Int, Future[Int]] = Sink.fold(0)(_ + _)
    val sink2: Sink[Int, Future[Int]] = Sink.reduce((previous: Int, input: Int) => previous + input)

    //function that combines both sink's materialized values
    def combineMat = (mat1: Future[Int], mat2: Future[Int]) => mat1.flatMap(m => mat2.map(_ + m))

    val flow: Flow[Int, Int, NotUsed] = Flow[Int].map((x: Int) => x * x)

    val grph: RunnableGraph[Future[Int]] = RunnableGraph.fromGraph(GraphDSL.create(sink1, sink2)(combineMat) {
      implicit builder =>
        (sk1, sk2) =>
          import GraphDSL.Implicits._
          val broadcast = builder.add(Broadcast[Int](2))
          source ~> broadcast.in

          broadcast.out(0) ~> flow ~> sk1
          broadcast.out(1) ~> flow ~> sk2

          ClosedShape
    })

    assert(Await.result(grph.run, Duration.Inf) == 28)
  }

  test("Constructing and combining Partial Graphs") {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val actorSystem = ActorSystem("system")
    implicit val materializer = ActorMaterializer()

    val list = List(List(1, 2), List(3, 4), List(5, 6))
    val source: Source[List[Int], NotUsed] = Source(list)
    val sink: Sink[List[Int], Future[List[Int]]] = Sink.fold(List(0))((listAcum, InputList: List[Int]) => for {
      n1 <- listAcum
      n2 <- InputList
    } yield n1 + n2)

    val flow: Flow[List[Int], List[Int], NotUsed] = Flow[List[Int]].map(x => x.map(_ + 1))

    val g1: Graph[UniformFanOutShape[List[Int], List[Int]], NotUsed] = GraphDSL.create(flow) {
      implicit builder =>
        (f) =>
          import GraphDSL.Implicits._

          val bcost: UniformFanOutShape[List[Int], List[Int]] = builder.add(Broadcast[List[Int]](1))
          f ~> bcost.in
          UniformFanOutShape(f.in, bcost.out(0))
    }

    val g2: RunnableGraph[Future[List[Int]]] = RunnableGraph.fromGraph(GraphDSL.create(sink) {
      implicit builder =>
        (sinkShape) =>
          import GraphDSL.Implicits._
          val graphPartial: UniformFanOutShape[List[Int], List[Int]] = builder.add(g1)
          val sourceShape: SourceShape[List[Int]] = builder.add(source)
          sourceShape ~> graphPartial ~> sinkShape
          ClosedShape
    })

    assert(Await.result(g2.run, Duration.Inf) == List(12, 13, 13, 14, 13, 14, 14, 15))
  }

  test("Combining Sources with simplified API") {
    implicit val actorSystem = ActorSystem("system")
    implicit val materializer = ActorMaterializer()

    val source = Source(1 to 5)
    val source2 = Source(20 to 25)
    val sink: Sink[Int, Future[String]] = Sink.fold("")(_ + " " + _)


    val mergedSources: Source[Int, NotUsed] = Source.combine(source, source2)(Merge(_))
    //Elements should produce: 1 20 2 21 3 22 4 23 5 24 25
    assert(Await.result(mergedSources.runWith(sink), Duration.Inf) == " 1 20 2 21 3 22 4 23 5 24 25")
  }

  test("Combining Sinks with simplified API using actors") {
    implicit val actorSystem = ActorSystem("system")
    implicit val materializer = ActorMaterializer()
    import actors._
    import actors.basicActor.Message

    val actorRef = actorSystem.actorOf(basicActor.props())

    val sink1 = Sink.actorRef(actorRef, Message("done"))

    val sink2 = Sink.foreach[Message](x => println("printing in local proccess: " + x.msg))
    //Message object is broadcasted
    val sink = Sink.combine(sink1, sink2)(Broadcast[Message](_))

    Source(List(Message("Hi!"), Message("konnichiwa"), Message("Ni hoa"))).runWith(sink)
    //test with side affects
    assert(true)
  }


  test("defining a bidiflow from functions and using it for the RunnableGraph construction") {
    import scala.util.Try
    implicit val actorSystem = ActorSystem("SYSTEM")
    implicit val materializer = ActorMaterializer();

    val list = List(0, 1, 2, 3)

    def mult(num: Int): Try[Int] = Try(10 * num)

    def div(num: Int): Try[Int] = Try(10 / num).recoverWith {
      case e: Exception => Try(-1)
    }

    val sink = Sink.fold(List.empty[Try[Int]])((list: List[Try[Int]], element: Try[Int]) => element :: list)

    val grp = RunnableGraph.fromGraph(GraphDSL.create(sink, sink)((_, _)) {
      implicit builder =>
        (sk1, sk2) => {
          import GraphDSL.Implicits._

          val s1: SourceShape[Int] = builder.add(Source(list))
          val s2 = builder.add(Source(list.reverse))

          val bidi: BidiShape[Int, Try[Int], Int, Try[Int]] = builder.add(BidiFlow.fromFunctions(mult, div))

          s1 ~> bidi.in1
                bidi.out1 ~> sk1
                bidi.in2 <~ s2
          sk2 <~ bidi.out2

          ClosedShape
        }
    })

    val result: (Future[List[Try[Int]]], Future[List[Try[Int]]]) = grp.run()

    assert(Await.result(result._1, Duration.Inf).map(x => x.get) == List(30, 20, 10, 0))
    assert(Await.result(result._2, Duration.Inf).map(x => x.get) == List(-1, 10, 1, 3))
  }

  test("defining a composed flow from a disconnected Sink and Source "){
    //verbously
    implicit val actorSystem = ActorSystem("system")
    implicit val materializer = ActorMaterializer()

      val grp = GraphDSL.create() {
        implicit builder =>
          val sink: SinkShape[Int] = builder.add(Sink.ignore)
          val source = builder.add(Source(1 to 5))
          FlowShape(sink.in, source.out)
      }

    Source(1 to 10).via(grp).runWith(Sink.foreach(println))
    assert(true)
  }


}
