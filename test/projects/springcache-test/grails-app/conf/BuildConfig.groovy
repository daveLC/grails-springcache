grails.project.class.dir = "target/classes"
grails.project.test.class.dir = "target/test-classes"
grails.project.test.reports.dir = "target/test-reports"
grails.project.dependency.resolution = {
	inherits "global"
	log "warn"
	repositories {
		grailsPlugins()
		grailsHome()
		grailsCentral()
		mavenLocal()
		mavenCentral()
		mavenRepo "http://repository.codehaus.org/"
	}
	dependencies {
		test "org.seleniumhq.selenium:selenium-firefox-driver:latest.integration"
		test("org.codehaus.groovy.modules.http-builder:http-builder:0.5.0") {
			excludes "groovy", "xml-apis", "commons-logging"
		}
	}
}
grails.plugin.location.springcache = "../../.."
