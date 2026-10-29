#!/usr/bin/env sh
#
# Lanzador mínimo del Gradle Wrapper.
#
# El jar y las propiedades sí están versionados (gradle/wrapper/), pero el script
# canónico que genera `gradle wrapper` no siempre viaja con el repo. Este shim
# hace exactamente lo mismo que él: invoca GradleWrapperMain, que descarga la
# distribución indicada en gradle-wrapper.properties y ejecuta la tarea pedida.
#
# Para regenerar los scripts oficiales (con las mejoras de cada versión):
#   gradle wrapper --gradle-version 8.14

APP_HOME=$(cd -- "$(dirname -- "$0")" && pwd)
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

exec "$JAVA_BIN" \
    -Dorg.gradle.appname=gradlew \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
