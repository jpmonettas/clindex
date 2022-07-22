.PHONY: release deploy clean help

clean:
	clj -T:build clean

clindex.jar:
	clj -T:build jar

install: clindex.jar
	mvn install:install-file -Dfile=target/clindex.jar -DpomFile=target/classes/META-INF/maven/com.github.jpmonettas/clindex/pom.xml

deploy:
	mvn deploy:deploy-file -Dfile=target/clindex.jar -DrepositoryId=clojars -DpomFile=target/classes/META-INF/maven/com.github.jpmonettas/clindex/pom.xml -Durl=https://clojars.org/repo

tag-release:
	git add CHANGELOG.md && \
	git commit -m "Updating CHANGELOG after $(version) release" && \
	git tag "v$(version)" && \
	git push origin master

help:
	@echo "For releasing to clojars run"
	@echo "make version=x.y.z release"
