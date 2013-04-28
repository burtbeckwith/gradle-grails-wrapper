package com.burtbeckwith.gradle.grails

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

/**
 * Based on https://github.com/ConnorWGarvey/gradle-grails-wrapper/ but adds Grails install
 * and task autodiscovery and removes downloading/intallation - use GVM for that.
 *
 * @author Burt Beckwith
 */
class GrailsPlugin implements Plugin<Project> {

	static class BuildProperties {
		String installBase = '/usr/local/javalib'
		String defaultVersion = '2.0.4'
		String devVersion = '2.3.0.BUILD-SNAPSHOT'
		String devHome = System.env.HOME + '/workspace.grails/grails-core'
		boolean verbose = false
		boolean stacktrace = false
	}

	void apply(Project project) {

		project.extensions.create 'grails', BuildProperties

		String grailsHome = findGrailsHome(project)
		if (!grailsHome) {
			return
		}

		for (String name in findCommands(grailsHome)) {
			registerGrailsCommandTask project, name, grailsHome
		}

		registerOtherTasks project
	}

	protected void registerOtherTasks(Project project) {

		Task pluginInit = project.task('plugin-init') << {
			ant.mkdir dir:'grails-app/conf/hibernate'
			ant.mkdir dir:'grails-app/conf/spring'
			ant.mkdir dir:'test/integration'
			ant.mkdir dir:'test/unit'
		}
		configureTask pluginInit, 'Creates empty directories to avoid build warnings'

		Task clean = project.task('clean') << {
			ant.delete {
				fileset dir:'.', includes:'*.log*'
			}
		}
		clean.dependsOn 'plugin-init', 'grails-clean'
		configureTask clean, 'Runs "grails clean" and deletes log files'

		Task test = project.task('test')
		test.dependsOn 'clean', 'grails-test-app'
		configureTask test, 'Runs "grails test-app"'

		Task packagePlugin = project.task('package-plugin')
		packagePlugin.dependsOn 'test', 'grails-package-plugin'
		packagePlugin.doLast { deleteEmptyDirs() }
		configureTask packagePlugin, 'Runs tests, "grails package-plugin", and deletes empty directories'

		Task postPackageCleanup = project.task('post-package-cleanup') << {
			deleteEmptyDirs()
		}
		configureTask postPackageCleanup, 'Deletes empty directories'
	}

	protected void deleteEmptyDirs() {
		for (name in ['grails-app', 'lib', 'scripts', 'src', 'test', 'web-app']) {
			deleteEmpty new File(name)
		}
	}

	protected void deleteEmpty(File f) {
		if (f.directory) {
			f.eachFile { deleteEmpty it }
			if (!f.list()) f.delete()
		}
	}

	protected void configureTask(Task task, String d) {
		task.configure {
			group 'Grails'
			description d
		}
	}

	protected void registerGrailsCommandTask(Project project, String target, String grailsHome) {

		Task task = project.task("grails-$target") << {

			String ext = System.getProperty('os.name').startsWith('Windows') ? '.bat' : ''
			List<String> command = [
				grailsHome + File.separatorChar + 'bin' + File.separatorChar + "grails$ext",
				'-plain-output'
			]

			if (project.grails.verbose) command << '--verbose'
			if (project.grails.stacktrace) command << '--stacktrace'

			command.addAll collectProperties(project, 'd').collect { "-D$it" }
			command << target
			command.addAll collectProperties(project, 'arg')

			project.exec {
				commandLine command
				environment System.getenv() + [GRAILS_HOME: grailsHome]
				standardInput = System.in
			}
		}

		configureTask task, "Run Grails $target"
	}

	protected List<String> collectProperties(Project project, String prefix) {

		List<String> result = []
		int index = 0
		try {
			while (true) {
				result << project."$prefix${index++}"
			}
		}
		catch (MissingPropertyException ex) {}
		return result
	}

	protected String findGrailsHome(Project project) {

		String grailsVersion
		String grailsVersionDir
		File applicationProperties = new File('application.properties')
		if (applicationProperties.exists()) {
			Properties properties = new Properties()
			properties.load new FileInputStream(applicationProperties)
			grailsVersion = properties['app.grails.version'].trim()
			println "Detected Grails $grailsVersion"
			if (grailsVersion == project.grails.devVersion) {
				grailsVersionDir = project.grails.devHome
			}
		}
		else {
			String grailsHome = System.env.GRAILS_HOME
			if (grailsHome) {
				println "Using explicit $grailsHome"
				grailsVersionDir = grailsHome
				Properties properties = new Properties()
				properties.load new FileInputStream(new File(grailsHome, 'build.properties'))
				grailsVersion = properties['grails.version'].trim()
			}
			else {
				println "Using default Grails $project.grails.defaultVersion"
				grailsVersion = project.grails.defaultVersion
			}
		}

		if (grailsVersion && !grailsVersionDir) {
			grailsVersionDir = "$project.grails.installBase/grails-$grailsVersion"
		}

		if (!new File(grailsVersionDir).exists()) {
			println "ERROR: Grails Version $grailsVersion not found: $grailsVersionDir"
			return null
		}

		grailsVersionDir
	}

	protected List<String> findCommands(String grailsHome) {

		def isLowerCase = { c -> Character.isLowerCase(c as char) }
		def isUpperCase = { c -> Character.isUpperCase(c as char) }

		def names = []
		for (File file in new File(grailsHome, 'scripts').listFiles()) {
			if (file.name.startsWith('_') || !file.name.toLowerCase().endsWith('groovy')) {
				continue
			}
			String name = file.name - '.groovy'
			if (name.endsWith('_')) {
				name = name[0..-2]
			}

			def sb = new StringBuilder(name)
			(1..sb.length() - 2).each { int i ->
				if (isLowerCase(sb[i-1]) && isUpperCase(sb[i]) && isLowerCase(sb[i+1])) {
					sb.insert i++, '-'
				}
			}

			names << sb.toString().toLowerCase()
		}

		names.sort()
	}
}
