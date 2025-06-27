# Stage 1: Build the JAR using Gradle
FROM gradle:8.4.0-jdk21 AS builder
COPY --chown=gradle:gradle . /home/gradle/project
WORKDIR /home/gradle/project
RUN gradle clean build --no-daemon

# Stage 2: Run the JAR with a minimal image
FROM eclipse-temurin:21-jdk
WORKDIR /app

# Copy built JAR and config into runtime image
COPY --from=builder /home/gradle/project/build/libs/*.jar app.jar
COPY user_colors.json user_colors.json

EXPOSE 42424
CMD ["java", "-jar", "app.jar"]
