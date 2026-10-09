FROM eclipse-temurin:25-jre-noble
WORKDIR /app

COPY --chown=10001:10001 build/libs/application.jar application.jar
RUN chmod 0444 /app/application.jar && chmod 1777 /tmp
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
