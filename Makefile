.PHONY: release deploy clean help

clean:
	-rm clindex.jar
	-rm pom.xml

clindex.jar:
	clj -A:jar clindex.jar

pom.xml:
	clj -Spom
	mvn versions:set -DnewVersion=$(version)

release: clean clindex.jar pom.xml

deploy:
	mvn deploy:deploy-file -Dfile=clindex.jar -DrepositoryId=clojars -DpomFile=pom.xml -Durl=https://clojars.org/repo

tag-release:
	git add CHANGELOG.md && \
	git commit -m "Updating CHANGELOG after $(version) release" && \
	git tag "v$(version)" && \
	git push origin master

help:
	@echo "For releasing to clojars run"
	@echo "make version=x.y.z release"
