# Replace with keel/python-runtime when that base image is published.
# Java agents use a JRE image until the shared runtime image exists.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/app.jar /app/app.jar
CMD ["java", "-jar", "/app/app.jar"]
