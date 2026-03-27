{ pkgs, lib, config, inputs, ... }:

{
  cachix.enable = true;

  packages = [
    pkgs.bloop
    pkgs.claude-code
    pkgs.git
    pkgs.natscli
    pkgs.opencode
    pkgs.scala-cli
  ];

  claude.code.enable = true;

  languages.java.jdk.package = pkgs.jdk25_headless;
  languages.scala = {
    enable = true;
    lsp.enable = true;
    sbt.enable = true;
  };

  enterShell = ''
    echo "zio-nats dev environment"
    echo "  sbt      – build & test"
    echo "  nats     – NATS CLI"
    echo "  scala-cli – scripting"
    echo ""
    echo "Run tests: sbt zioNatsTest/test  (requires Docker)"
  '';

  enterTest = ''
    sbt zioNatsTest/Test/compile
  '';
}
