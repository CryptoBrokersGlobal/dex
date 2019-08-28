import WavesExtensionDockerPlugin.autoImport._

enablePlugins(WavesExtensionDockerPlugin, ItTestPlugin)

description := "DEX integration tests"
libraryDependencies ++= Dependencies.itTest

docker := docker.dependsOn(LocalProject("waves-integration-it") / docker).value
inTask(docker)(
  Seq(
    baseImage := "com.wavesplatform/waves-integration-it:latest",
    imageNames := Seq(ImageName("com.wavesplatform/dex-it")),
    exposedPorts := Set(6886),
    additionalFiles ++= Seq(
      (LocalProject("dex") / Universal / stage).value,
      (Test / resourceDirectory).value / "template.conf",
      (Test / sourceDirectory).value / "container" / "wallet"
    )
  ))
