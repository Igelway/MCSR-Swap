# Format all Java code with Spotless
format-java:
    cd fabric-mod && ./gradlew spotlessApply
    cd velocity-plugin && mvn spotless:apply

# Check Java code formatting
format-java-check:
    cd fabric-mod && ./gradlew spotlessCheck
    cd velocity-plugin && mvn spotless:check
