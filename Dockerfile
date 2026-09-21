FROM harbor.allianz-tr.local/alz-base/redhat/ubi9-temurin-jdk25-rootless:u9.6-j25_36
VOLUME /tmp
ARG JAR_FILE=target/sbm-declaration-services.jar
WORKDIR /app
# ENTRYPOINT bu adla calistiriyor; iki ad ayni olmali.
COPY ${JAR_FILE} /app/sbm-declaration-services.jar
EXPOSE 8080
ENV TZ=Europe/Istanbul
USER alzusr

ENTRYPOINT source /vault/secrets/config && \
    exec java $JAVA_OPTS -jar $JAVA_ARGS \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.authenticate=false  \
    -DsysType=${SYS_TYPE} \
    -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE} \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.authenticate=false  \
    -Dspring.config.location=/app-config/,/app-config-common/ \
    -Djava.awt.headless=true  \
    -Dlogging.path=\. \
    -Dfile.encoding=UTF-8 \
    -Duser.country=US \
    -Duser.language=en \
    -Dspring.application.name=sbm-declaration-services \
    -Duser.timezone="Europe/Istanbul" \
    /app/sbm-declaration-services.jar
