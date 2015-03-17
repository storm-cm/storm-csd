import java.util.regex.Matcher
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class Param {
    String key
    String type
    StringBuilder comment

    String toString() {
        return "${key}(${type}, ${comment})"
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

        switch (type) {
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

        // XXX fetch defaults from default.yaml
        switch(key) {
        case 'nimbus.thrift.port':
            result['required'] = true // referenced by peerConfigGenerators, which cause a nasty NullPointerException in the Cloudera Manager server if the referenced parameter is not set
            result['default'] = 6627
            break
        case 'logviewer.port': // default required because of externalLink in SDL file
            result['default'] = 8000
            break
        case 'ui.port': // default required because of externalLink in SDL file
            result['default'] = 8080
            break
        case 'drpc.port':
            result['default'] = 3772
            break
        case 'drpc.invocations.port':
            result['default'] = 3773
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

        // CM emits 'false' for all booleans that don't have a value specified!
        // XXX fetch from default.yaml--then this can disappear
        if (result['type'] == 'boolean') {
            switch(key) {
            case 'nimbus.reassign':
                result['default'] = true
                break
            default:
                throw new RuntimeException("Unknown default for boolean value: ${key}")
            }
        }

        return result
    }
}

def parseParams() {
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
            break
        case ~/.*?public static final Object [A-Z_]+?_SCHEMA\s*=\s*([^;]+);.*/:
            p.type = Matcher.lastMatcher[0][1]
            
            result << p
            break
        }
    }
    return result
}

def knownParams = parseParams()

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
