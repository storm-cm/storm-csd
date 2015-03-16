SHELL := /bin/bash

CSD_VERSION := 0.1$(if $(BUILD_NUMBER),.$(BUILD_NUMBER),-SNAPSHOT)

GROOVY := $(if $(GROOVY_HOME),$(GROOVY_HOME)/bin/groovy,groovy)
JAVA := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)
JAR := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/jar,jar)
MVN := mvn
VALIDATOR := cm_ext/validator/target/validator.jar
WGET := wget -c --no-use-server-timestamps

STORM-$(CSD_VERSION).jar: descriptor/service.sdl scripts/start scripts/config images/storm.png $(VALIDATOR)
	$(JAR) cf $@ $(filter-out $(VALIDATOR),$^)

descriptor/service.sdl: descriptor/service.sdl.in extract_config.groovy stamp-storm
	$(GROOVY) $(filter %.groovy,$^) < storm/storm-core/src/jvm/backtype/storm/Config.java > $@
	if ! $(JAVA) -jar $(VALIDATOR) -s $@; then mv $@ $@.tmp; exit 1; fi

stamp-storm:
	git clone -b v0.9.3 https://github.com/apache/storm
	touch $@

$(VALIDATOR): stamp-cm_ext
	cd cm_ext && $(MVN) -pl validator package

stamp-cm_ext:
	git clone https://github.com/cloudera/cm_ext.git
	touch $@

.PHONY: clean
clean:
	rm -rf stamp-* *.jar *.tmp aux/storm.yaml aux/storm.yaml.tmp cm_ext storm

.DEFAULT_GOAL := STORM-$(CSD_VERSION).jar
