FROM amazoncorretto:21
WORKDIR /app
COPY app.jar authService.jar
EXPOSE 9898
ENTRYPOINT ["java", "-jar", "authService.jar"]
