VERSION=latest
PROJECT=thingsboard
APP=performance-tests

build:
	docker build --pull -t ${PROJECT}/${APP}:${VERSION} .
	rm project-src.tar.gz pom.xml

push: build
	docker push ${PROJECT}/${APP}:${VERSION}