FROM openjdk:17-jre
EXPOSE 8080
ADD target/springboot3-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]


