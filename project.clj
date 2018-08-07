(defproject artstor-group-service "0.0.7-SNAPSHOT"
  :description "AIW Group Service"
  :url "http://www.artstor.org/"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/data.codec "0.1.0"]
                 [org.clojure/core.cache "0.6.5"]
                 [org.clojure/test.check "0.9.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.sharetribe/aws-sig4 "0.1.2"]
                 [com.amazonaws/aws-java-sdk-core "1.11.98"]
                 [clj-sequoia "3.0.3"]
                 [ring "1.6.2"]
                 [ring/ring-json "0.4.0"]
                 [ring/ring-mock "0.3.0"]
                 [ring-logger "0.7.7"]
                 [clj-time "0.13.0"]
                 [clojurewerkz/elastisch "3.0.0-beta1"]
                 [yesql "0.5.3"]
                 [com.mchange/c3p0 "0.9.5-pre10"]
                 [com.oracle/ojdbc7 "12.1.0.1"]
                 [org.postgresql/postgresql "9.4-1201-jdbc41"]
                 [ragtime "0.6.3"]
                 [environ "1.1.0"]
                 [metosin/compojure-api "1.2.0-alpha5"]
                 [prismatic/schema "1.1.3"]
                 [buddy/buddy-auth "1.4.1"]
                 [org.ithaka/clj-iacauth "1.1.3"]
                 [org.ithaka.artstor/captains-common "0.4.2"]
                 [clojure-future-spec "1.9.0-alpha15"]
                 ;; Use Logback as the main logging implementation:
                 [ch.qos.logback/logback-classic "1.1.9"]
                 [ch.qos.logback/logback-core "1.1.9"]
                 ;; Logback implements the SLF4J API:
                 [org.slf4j/slf4j-api "1.7.22"]
                 ;; Redirect Apache Commons Logging to Logback via the SLF4J API:
                 [org.slf4j/jcl-over-slf4j "1.7.22"]
                 ;; Redirect Log4j 1.x to Logback via the SLF4J API:
                 [org.slf4j/log4j-over-slf4j "1.7.22"]
                 ;; Redirect Log4j 2.x to Logback via the SLF4J API:
                 [org.apache.logging.log4j/log4j-to-slf4j "2.7"]
                 ;; Redirect OSGI LogService to Logback via the SLF4J API
                 [org.slf4j/osgi-over-slf4j "1.7.22"]
                 ;; Redirect java.util.logging to Logback via the SLF4J API.
                 ;; Requires installing the bridge handler, see README:
                 [org.slf4j/jul-to-slf4j "1.7.22"]]

  :exclusions  [;; Exclude transitive dependencies on all other logging
                ;; implementations, including other SLF4J bridges.
                commons-logging
                log4j
                org.apache.logging.log4j/log4j
                org.slf4j/simple
                org.slf4j/slf4j-jcl
                org.slf4j/slf4j-nop
                org.slf4j/slf4j-log4j12
                org.slf4j/slf4j-log4j13]
  :profiles {:test {:env {:artstor-group-db-url "jdbc:h2:/tmp/artstor-group-test.db"
                          :artstor-oracle-db-url "jdbc:h2:/tmp/artstor-group-test.db"}
                    :dependencies [[cheshire "5.7.0"]
                                   [szew/h2 "0.1.1"]
                                   [org.clojure/tools.nrepl "0.2.12"]
                                   [org.clojure/test.check "0.9.0"]
                                   [ragtime "0.6.3"]]
                    :ragtime {:database "jdbc:h2:/tmp/artstor-group-test.db"}}}
  :plugins [[lein-modules "0.3.11"]
            [lein-ring "0.10.0"]
            [lein-environ "1.1.0"]
            [lein-marginalia "0.9.0"]]
  :ring {:handler artstor-group-service.core/app :port 8080})

