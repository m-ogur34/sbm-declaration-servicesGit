FROM harbor.allianz-tr.local/alz-base/redhat/ubi9-temurin-jdk25-rootless:u9.6-j25_36

ARG JAR_FILE=target/sbm-declaration-services.jar

ENV TZ=Europe/Istanbul \
    LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Europe/Istanbul"

WORKDIR /app

COPY ${JAR_FILE} /app/app.jar

RUN addgroup -S allianz && adduser -S allianz -G allianz && chown -R allianz:allianz /app
USER allianz

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O - http://localhost:8080/sbm-declaration-services/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_ARGS -jar /app/app.jar"]
