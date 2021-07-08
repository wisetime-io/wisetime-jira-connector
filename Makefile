build-fast:
	./gradlew build -x test -x check

clean:
	./gradlew clean

test:
	./gradlew check test