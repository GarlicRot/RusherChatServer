# Use official Gradle image to build the project
FROM gradle:8.4.0-jdk21 AS builder
COPY --chown=gradle:gradle . /home/gradle/project
WORKDIR /home/gradle/project
RUN gradle build --no-daemon

# Use a slim JDK to run the app
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=builder /home/gradle/project/build/libs/*.jar app.jar

# Port the chat server listens on
EXPOSE 42424

# Run the server
ENTRYPOINT ["java", "-jar", "app.jar"]
