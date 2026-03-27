{ pkgs, lib, config, inputs, ... }:

let
  pkgs-unstable = import inputs.nixpkgs-unstable {
    system = pkgs.stdenv.system;
    config.allowUnfree = true;
  };
in

{
  cachix.enable = true;

  packages = [
    pkgs.bloop
    pkgs-unstable.claude-code
    pkgs.git
    pkgs.natscli
    pkgs-unstable.opencode
    pkgs.scala-cli
  ];

  claude.code.enable = true;

  languages.java.jdk.package = pkgs.jdk25_headless;
  languages.scala = {
    enable = true;
    lsp.enable = true;
    sbt.enable = true;
  };

  # ---------------------------------------------------------------------------
  # Services
  # ---------------------------------------------------------------------------

  # services.nats = {
  #   enable = true;
  #   jetstream.enable = true;
  # };

  # ---------------------------------------------------------------------------
  # Scripts
  # ---------------------------------------------------------------------------

  scripts = {
    # Format all sources (main, test, and sbt files)
    format.exec = "sbt scalafmtAll";

    # Verify formatting and compile everything without running tests
    check.exec = "sbt scalafmtCheckAll compile zioNatsTest/Test/compile";

    # Run example programs — requires NATS: devenv up [-d] nats
    "run-examples".exec = "sbt zioNatsExamples/run";
  };

  # ---------------------------------------------------------------------------
  # Git hooks
  # ---------------------------------------------------------------------------

  pre-commit.hooks.scalafmt-check = {
    enable = true;
    name = "scalafmt";
    entry = "sbt scalafmtCheckAll";
    language = "system";
    pass_filenames = false;
    files = "\\.(scala|sbt)$";
  };

  # ---------------------------------------------------------------------------
  # Shell
  # ---------------------------------------------------------------------------

  enterShell = ''
    echo "zio-nats dev environment"
    echo ""
    echo "Scripts:"
    echo "  format        format all sources"
    echo "  check         verify formatting + compile (no tests)"
    echo "  run-examples  run example programs   (requires NATS service)"
    echo ""
    echo "Services:"
    # echo "  devenv up [-d] nats    start NATS + JetStream on :4222"
    # echo "  devenv processes down  stop running services"
    echo ""
    echo "Other tools:  nats  scala-cli  bloop"
  '';

  # ---------------------------------------------------------------------------
  # CI / devenv test
  # ---------------------------------------------------------------------------

  enterTest = ''
    sbt zioNatsTest/test
  '';
}
