JAVA_HOME := $(shell cat .java-home)
export JAVA_HOME

dist_bundles = command http jade jdbc mustache smtp less

dist_dir = dist/reka

.PHONY: clean clean-build clean-dist upload-s3 test install-main build-main build-bundles

all: build

build:
	@mvn -DskipTests clean package
	@mkdir -p build/bundles
	@find bundles -name 'reka-*.jar' -exec cp {} build/bundles/ ';'
	@find main/reka-main -name 'reka-*.jar' -exec cp {} build/ ';'

test:
	@mvn test

build-main:
	@cd main && mvn -DskipTests clean package

build-bundles: install-main
	@cd bundles && mvn -DskipTests clean package

install-main:
	@cd main && mvn -DskipTests clean install

clean-build:
	@rm -rf build

clean-dist:
	@rm -rf dist

clean: clean-build clean-dist

dist: build
	@mkdir -p $(dist_dir)
	@cp -r dist-resources/* $(dist_dir)/
	@mkdir -p $(dist_dir)/lib/
	@mkdir -p $(dist_dir)/lib/bundles
	@mkdir -p $(dist_dir)/etc/apps
	@cp build/reka-main*.jar $(dist_dir)/lib/reka-server.jar
	@for bundle in $(dist_bundles); do\
		cp build/bundles/reka-$$bundle-* $(dist_dir)/lib/bundles/ ; \
	done
	@for bundle in `ls $(dist_dir)/lib/bundles`; do\
		echo "bundle ../lib/bundles/$$bundle" >> $(dist_dir)/etc/config.reka; \
	done
	@echo "app api @include(apps/api.reka)" >> $(dist_dir)/etc/config.reka
	@cd dist && tar zcvf reka-server.tar.gz reka
	@echo made dist/reka-server.tar.gz

run: dist
	@dist/reka/bin/reka-server

run-nolog: dist
	@JAVA_OPTS=-Dlog4j.configurationFile=main/reka-main/log4j2-errors-only.xml dist/reka/bin/reka-server

run-debug: dist
	@JAVA_OPTS=-Dlog4j.configurationFile=main/reka-main/log4j2-debug.xml dist/reka/bin/reka-server

upload-s3: dist
	@aws s3 \
		cp dist/reka-server.tar.gz s3://reka/reka-server.tar.gz	\
		--grants \
			read=uri=http://acs.amazonaws.com/groups/global/AllUsers

