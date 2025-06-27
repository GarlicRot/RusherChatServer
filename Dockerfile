# Stage 2: Run the JAR with a minimal image
FROM eclipse-temurin:21-jdk
WORKDIR /app

# Copy the built JAR
COPY --from=builder /home/gradle/project/build/libs/*.jar app.jar

# Copy user_colors.json from the host into the container
COPY user_colors.json user_colors.json

EXPOSE 42424
CMD ["java", "-jar", "app.jar"]
