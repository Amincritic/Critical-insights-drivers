# Offline Gradle wrapper

This package is configured so `./gradlew` does not reach `services.gradle.org`.

Expected local file:

```text
gradle/wrapper/gradle-7.6-bin.zip
```

Place the official Gradle 7.6 binary distribution zip at that path before running `./gradlew` on an offline machine.

Why the zip is not included here: the Gradle distribution is about 122 MB and should be managed as a build artifact/cache, not committed into source.

Example online prep step on a machine with internet:

```bash
mkdir -p gradle/wrapper
curl -L -o gradle/wrapper/gradle-7.6-bin.zip https://services.gradle.org/distributions/gradle-7.6-bin.zip
./gradlew --version
```

After that, copy this project directory to the offline target.
