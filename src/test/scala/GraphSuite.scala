import java.nio.ByteOrder

import org.scalatest.FunSuite
import akka.{Done, NotUsed}
import akka.actor.ActorSystem
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


  //http://doc.akka.io/docs/akka/current/scala/stream/stream-graphs.html
  test("bidirectional flows") {
    trait Message
    case class Ping(id: Int) extends Message
    case class Pong(id: Int) extends Message

    def toBytes(msg: Message): ByteString = {
      implicit val order = ByteOrder.LITTLE_ENDIAN
      msg match {
        case Ping(id) => ByteString.newBuilder.putByte(1).putInt(id).result()
        case Pong(id) => ByteString.newBuilder.putByte(2).putInt(id).result()
      }
    }

    def fromBytes(bytes: ByteString): Message = {
      implicit val order = ByteOrder.LITTLE_ENDIAN
      val it = bytes.iterator
      it.getByte match {
        case 1 => Ping(it.getInt)
        case 2 => Pong(it.getInt)
        case other => throw new RuntimeException(s"parse error: expected 1|2 got $other")
      }
    }

    val codecVerbose = BidiFlow.fromGraph(GraphDSL.create() { b =>
      // construct and add the top flow, going outbound
      val outbound = b.add(Flow[Message].map(toBytes))
      // construct and add the bottom flow, going inbound
      val inbound = b.add(Flow[ByteString].map(fromBytes))
      // fuse them together into a BidiShape
      BidiShape.fromFlows(outbound, inbound)
    })

    // this is the same as the above
    val codec = BidiFlow.fromFunctions(toBytes _, fromBytes _)


    val framing = BidiFlow.fromGraph(GraphDSL.create() { b =>
      implicit val order = ByteOrder.LITTLE_ENDIAN

      def addLengthHeader(bytes: ByteString) = {
        val len = bytes.length
        ByteString.newBuilder.putInt(len).append(bytes).result()
      }

      class FrameParser extends GraphStage[FlowShape[ByteString, ByteString]] {

        val in = Inlet[ByteString]("FrameParser.in")
        val out = Outlet[ByteString]("FrameParser.out")
        override val shape = FlowShape.of(in, out)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

          // this holds the received but not yet parsed bytes
          var stash = ByteString.empty
          // this holds the current message length or -1 if at a boundary
          var needed = -1

          setHandler(out, new OutHandler {
            override def onPull(): Unit = {
              if (isClosed(in)) run()
              else pull(in)
            }
          })
          setHandler(in, new InHandler {
            override def onPush(): Unit = {
              val bytes = grab(in)
              stash = stash ++ bytes
              run()
            }

            override def onUpstreamFinish(): Unit = {
              // either we are done
              if (stash.isEmpty) completeStage()
              // or we still have bytes to emit
              // wait with completion and let run() complete when the
              // rest of the stash has been sent downstream
              else if (isAvailable(out)) run()
            }
          })

          private def run(): Unit = {
            if (needed == -1) {
              // are we at a boundary? then figure out next length
              if (stash.length < 4) {
                if (isClosed(in)) completeStage()
                else pull(in)
              } else {
                needed = stash.iterator.getInt
                stash = stash.drop(4)
                run() // cycle back to possibly already emit the next chunk
              }
            } else if (stash.length < needed) {
              // we are in the middle of a message, need more bytes,
              // or have to stop if input closed
              if (isClosed(in)) completeStage()
              else pull(in)
            } else {
              // we have enough to emit at least one message, so do it
              val emit = stash.take(needed)
              stash = stash.drop(needed)
              needed = -1
              push(out, emit)
            }
          }
        }
      }

      val outbound = b.add(Flow[ByteString].map(addLengthHeader))
      val inbound = b.add(Flow[ByteString].via(new FrameParser))
      BidiShape.fromFlows(outbound, inbound)
    })
  }
}
