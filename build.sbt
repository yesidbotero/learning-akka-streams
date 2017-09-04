name := "LearningStreams"

version := "1.0"

scalaVersion := "2.12.3"

libraryDependencies ++= Seq (
  "com.typesafe.akka"     %% "akka-stream"          % "2.4.17",
  "com.typesafe.akka"     %% "akka-stream-testkit"  % "2.4.17"  % "test",
  "org.scalactic"         %% "scalactic"            % "3.0.1",
  "org.scalatest"         %% "scalatest"            % "3.0.1" % "test"
)

        