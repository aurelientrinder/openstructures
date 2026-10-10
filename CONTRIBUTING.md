# Contributing

Issues and pull requests are welcome, translations too. To report a bug or ask for a feature, open an
[issue](https://github.com/aurelientrinder/openstructures/issues/new/choose): the templates ask for what helps.

## Build and run

All it takes is a JDK 21 or later: plain Java + Swing, no dependencies, no build tool.

On Windows, from the repository's folder:

```bat
run.cmd          :: builds rdm.jar and starts it
build.cmd        :: only builds rdm.jar (then: java -jar rdm.jar [file.rdm])
```

On Linux or macOS:

```sh
javac -encoding UTF-8 --release 21 -d out/classes $(find src -name '*.java')
jar --create --file rdm.jar --main-class rdm.App -C out/classes .
java -jar rdm.jar
```

`out/` and `rdm.jar` are build outputs, ignored by git. In IntelliJ IDEA, open the folder: `rdm-java.iml` declares
`src` and `test` and nothing else.

## Run the checks

`test/rdm/Verify.java` checks the solver and the sections against closed-form results, file reading, and that
every language has every text. It prints one line per check and exits with code 1 if any fails.

```bat
verify.cmd
```

```sh
javac -encoding UTF-8 --release 21 -d out/test $(find src test -name '*.java')
java -Dstdout.encoding=UTF-8 -cp out/test rdm.Verify
```

The same checks run on Linux, Windows and macOS for every push and pull request (`.github/workflows/checks.yml`).

## Submit a change

1. Fork the repository and make a branch from `main`.
2. Keep each commit to one change. The message's first line says what the change does, in the imperative
   ("Add point loads…", "Keep the labels off…"); the lines after it say why, and anything a reader of the
   diff would not see.
3. A change to the solver or the sections comes with a check in `Verify` against a closed-form result (with
   the formula in the check's name). A new text goes in both `En.java` and `Fr.java`, under the same key and with
   the same arguments; `Verify` checks it.
4. Run the checks, and try the change in the window.
5. Open a pull request against `main` saying what it changes and how you tried it; a screenshot helps for
   anything visible. The checks must pass.

To add a language, copy `src/rdm/i18n/Fr.java`, translate it and add it to `Tr.Lang`.

Releases are published by the maintainer, by pushing a `vX.Y.Z` tag (`.github/workflows/release.yml`).

By contributing, you agree that your contribution is licensed under the [MIT licence](LICENSE).
