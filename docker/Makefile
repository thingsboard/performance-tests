VERSION=latest
PROJECT=thingsboard
APP=performance-tests

build:
	tar -zcvf project-src.tar.gz ../src
	cp ../pom.xml .
	docker build --pull -t ${PROJECT}/${APP}:${VERSION} .
	rm project-src.tar.gz pom.xml

push: build
	docker push ${PROJECT}/${APP}:${VERSION}