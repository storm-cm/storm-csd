@Grapes([
   @Grab(group='org.yaml', module='snakeyaml', version='1.15')
])

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.yaml.snakeyaml.Yaml
import java.util.regex.Matcher

def parseDefaults() {
    def yaml = new Yaml()
    def defaults
    new File('storm/conf/defaults.yaml').withInputStream {
        defaults = yaml.load(it)
    }
    return defaults
}

class Param {
    String key
    String type
    Object default_
    StringBuilder comment

    String toString() {
        return "${key}(${type}, ${default_}, ${comment})"
    }

    def toJson() {
        assert key != null
        assert type != null
        assert comment != null

        def result = [
            name:'storm_' + key.replaceAll(/\./, '_'),
            label:key,
            description:comment.toString(),
            configName:key,
        ]

        switch(key) {
        case 'storm.id': // topology-specific config makes no sense in storm.yaml
        case ~/topology\..*/: // topology-specific config makes no sense in storm.yaml
        case 'nimbus.host': // determined by service topology in CM
        case 'storm.zookeeper.servers': // available in ZK_QUORUM environment variable
        case 'storm.zookeeper.port': // available in ZK_QUORUM environment variable
        case 'transactional.zookeeper.servers': // fall back to zookeeper.serversw
        case 'transactional.zookeeper.port': // fall back to zookeeper.port
        case 'storm.cluster.mode': // always run in distributed mode
        case 'storm.local.mode.zmq': // ignored in distributed mode
        case 'storm.local.hostname': // makes no sense to set this service-wide
        case 'supervisor.enable': // only used by storm-core unit tests
        case 'drpc.servers': // determined by service topology in CM (XXX not implemented)
        case 'dev.zookeeper.path': // only used in development
            return null
        }

        switch(type) {
        case 'String.class':
        case 'ConfigValidation.StringOrStringListValidator': // currently only worker.childopts
            result['type'] = 'string'
            break
        case 'Boolean.class':
            result['type'] = 'boolean'
            break
        case 'ConfigValidation.DoubleValidator':
            result['type'] = 'double'
            break
        case 'ConfigValidation.IntegersValidator':
        case 'ConfigValidation.StringsValidator':
            result['type'] = 'string_array'
            break
        case 'ConfigValidation.IntegerValidator':
        case 'ConfigValidation.PowerOf2Validator':
            result['type'] = 'long'
            break
        case 'Map.class':
            // XXX conversion from array of 'foo=bar' to yaml map not yet implemented
            result['type'] = 'string_array'
            result['separator'] = ','
            break
        default:
            System.err.println "Ignoring unknown type for ${this}"
            return null
        }

        switch(key) {
        case ~/.*\.port/:
            result['type'] = 'port'
            result['required'] = true
            result['configurableInWizard'] = true
            break
        case ~/.*\.secs/:
            result['unit'] = 'seconds'
            break
        case ~/.*_ms/:
        case ~/.*\.millis/:
        case ~/.*\.timeout/: // must be tested *after* .secs since there are options named foo.timeout.secs
            result['units'] = 'milliseconds'
            break
        case ~/\.*\.buffer\.size/:
        case ~/\.*\.batch\.size/:
            result['unit'] = 'bytes'
            break
        }

        switch(key) {
        case 'nimbus.thrift.port':
            result['required'] = true // referenced by peerConfigGenerators, which cause a nasty NullPointerException in the Cloudera Manager server if the referenced parameter is not set
            result['default'] = 6627
            break
        case 'ui.port': // default required because of externalLink in SDL file
            result['default'] = 8080
            break
        case 'java.library.path':
            result['type'] = 'path_array'
            result['separator'] = ':'
            result['pathType'] = 'serviceSpecific'
            result['default'] = ['/opt/cloudera/parcels/CDH/lib/hadoop/lib/native']
            break
        case 'storm.local.dir':
            result['type'] = 'path'
            result['pathType'] = 'localDataDir'
            result['mode'] = '0700'
            result['required'] = true
            result['default'] = '/var/lib/storm'
            result['configurableInWizard'] = true
            break
        }

        if (result.get('default') == null && default_ != null) {
            result['default'] = default_
        }

        return result
    }
}

def parseParams(def defaults) {
    def result = []
    def p
    System.in.eachLine {
        switch (it) {
        case ~/.*?\/\*.*/:
            p = new Param()
            break
        case ~/.*?\* (.*).*/:
            if (p.comment == null)
                p.comment = new StringBuilder()
            else
                p.comment << ' '
            p.comment << Matcher.lastMatcher[0][1]
            break
        case ~/.*?public static final String [A-Z_]+\s*=\s*"([^"]+)".*/:
            p.key = Matcher.lastMatcher[0][1]
            p.default_ = defaults.get(p.key)
            break
        case ~/.*?public static final Object [A-Z_]+?_SCHEMA\s*=\s*([^;]+);.*/:
            p.type = Matcher.lastMatcher[0][1]

            result << p
            p = null
            break
        }
    }
    return result
}

def knownParams = parseParams(parseDefaults())

def params = []
knownParams.each {
    try {
        def p = it.toJson()
        if (p != null) params << p
    } catch (Exception e) {
        throw new RuntimeException("Bad parameter: ${it}", e)
    }
}

def js = new JsonSlurper()
def sd = js.parse(new File('descriptor/service.sdl.in'))
sd['parameters'] += params

def jo = new JsonOutput()
System.out << JsonOutput.prettyPrint(jo.toJson(sd))
