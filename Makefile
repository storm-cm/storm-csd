SHELL := /bin/bash

CSD_VERSION := 0.1$(if $(BUILD_NUMBER),.$(BUILD_NUMBER),-SNAPSHOT)

JAVA := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)
MVN := mvn
VALIDATOR := cm_ext/validator/target/validator.jar
WGET := wget -c --no-use-server-timestamps
JAR := jar

STORM-$(CSD_VERSION).jar: descriptor/service.sdl scripts/start images/storm.png $(VALIDATOR)
	$(JAVA) -jar $(VALIDATOR) -s $(filter %.sdl,$^)
	$(JAR) cf $@ $(filter-out $(VALIDATOR),$^)

$(VALIDATOR): stamp-cm_ext
	cd cm_ext && $(MVN) -pl validator package

stamp-cm_ext:
	git clone https://github.com/cloudera/cm_ext.git
	touch $@

.PHONY: clean
clean:
	rm -rf stamp-* STORM-$(CSD_VERSION).jar cm_ext

.DEFAULT_GOAL := STORM-$(CSD_VERSION).jar
