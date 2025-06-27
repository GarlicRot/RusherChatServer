# Stage 1: Build the JAR using Gradle
FROM gradle:8.4.0-jdk21 AS builder
COPY --chown=gradle:gradle . /home/gradle/project
WORKDIR /home/gradle/project
RUN gradle clean build --no-daemon

# Stage 2: Run the JAR with a minimal image
FROM eclipse-temurin:21-jdk
WORKDIR /app

# Copy only the built JAR (assumes only one JAR in the build/libs dir)
COPY --from=builder /home/gradle/project/build/libs/*.jar app.jar

# The port your app listens on
EXPOSE 42424

# Default command to run your application
CMD ["java", "-jar", "app.jar"]
