
docker:
	./gradlew clean jibBuild

docker-arm64v8:
	./gradlew clean jibBuild -PtargetArch=arm64v8

build-fast:
	./gradlew build -x test -x check

clean:
	./gradlew clean

test:
	./gradlew check test