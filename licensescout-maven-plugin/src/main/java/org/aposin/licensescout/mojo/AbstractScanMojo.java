/**
 * Copyright 2019 Association for the promotion of open-source insurance software and for the establishment of open interface standards in the insurance industry (Verein zur Förderung quelloffener Versicherungssoftware und Etablierung offener Schnittstellenstandards in der Versicherungsbranche)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aposin.licensescout.mojo;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.aposin.licensescout.archive.ArchiveType;
import org.aposin.licensescout.configuration.ConfigFileHandler;
import org.aposin.licensescout.configuration.ConfigFileParameters;
import org.aposin.licensescout.configuration.DatabaseConfiguration;
import org.aposin.licensescout.configuration.FilesystemConfigFileHandler;
import org.aposin.licensescout.configuration.Output;
import org.aposin.licensescout.configuration.ZipConfigFileHandler;
import org.aposin.licensescout.execution.ExecutionParameters;
import org.aposin.licensescout.execution.Executor;
import org.aposin.licensescout.execution.LicenseScoutExecutionException;
import org.aposin.licensescout.execution.LicenseScoutFailOnErrorException;
import org.aposin.licensescout.execution.StandardReportExporterFactory;
import org.aposin.licensescout.license.LegalStatus;
import org.aposin.licensescout.maven.utils.ArtifactHelper;
import org.aposin.licensescout.maven.utils.ArtifactItem;
import org.aposin.licensescout.maven.utils.IRepositoryParameters;
import org.aposin.licensescout.maven.utils.MavenLog;
import org.aposin.licensescout.util.ILFLog;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Scans directory for licenses (either JAVA Jars or NPM packages).
 *
 */
public abstract class AbstractScanMojo extends AbstractMojo implements IRepositoryParameters {

    /**
     * Directory to scan for archives.
     * <p>This is the directory where the LicenseScout will start to traverse directories recursively.
     * If the directory does not exist, the LicenseScout will be terminated with an exception.</p>
     * 
     * @since 1.1
     */
    @Parameter(property = "scanDirectory", required = false)
    private File scanDirectory;

    /**
     * Location of the output file (will be combined with output filename).
     * <p>The directory is created if it does not exist.</p>
     * 
     * @since 1.0
     */
    @Parameter(defaultValue = "${project.build.directory}/licensescout", property = "outputDirectory", required = false)
    private String outputDirectory;

    /**
     * Specification of output types and filenames.
     * 
     * @see Output
     * @since 1.2
     */
    @Parameter(property = "outputs", required = false)
    private List<Output> outputs;

    /**
     * Name of the file to read known licenses from.
     * 
     * @since 1.1
     */
    @Parameter(property = "licensesFilename", required = false)
    private File licensesFilename;

    /**
     * Name of the file to read known providers from.
     * 
     * @since 1.2.6
     */
    @Parameter(property = "providersFilename", required = false)
    private File providersFilename;

    /**
     * Name of the file to read license notices from.
     * 
     * @since 1.2.6
     */
    @Parameter(property = "noticesFilename", required = false)
    private File noticesFilename;

    /**
     * Name of the file to read checked archives from.
     * 
     * @since 1.2.6
     */
    @Parameter(defaultValue = "checkedarchives.csv", property = "checkedArchivesFilename", required = false)
    private File checkedArchivesFilename;

    /**
     * Name of the file to read license URL mappings from.
     * 
     * @since 1.2.6
     */
    @Parameter(defaultValue = "urlmappings.csv", property = "licenseUrlMappingsFilename", required = false)
    private String licenseUrlMappingsFilename;

    /**
     * Name of the file to read license name mappings from.
     * 
     * @since 1.2.6
     */
    @Parameter(defaultValue = "namemappings.csv", property = "licenseNameMappingsFilename", required = false)
    private String licenseNameMappingsFilename;

    /**
     * Name of the file to read global filter patterns from.
     * 
     * @since 1.2.6
     */
    @Parameter(defaultValue = "globalFilters.csv", property = "globalFiltersFilename", required = false)
    private String globalFiltersFilename;

    /**
     * Name of the file to read of vendor names to filter out from.
     * This is alternative to {@link #filteredVendorNames}.
     * If both are given, the entries are merged.
     * 
     * @since 1.1
     */
    @Parameter(property = "filteredVendorNamesFilename", required = false)
    private String filteredVendorNamesFilename;

    /**
     * If cleaning the output should be active.
     * 
     * @since 1.2.6
     */
    @Parameter(defaultValue = "false", property = "cleanOutputActive", required = false)
    private boolean cleanOutputActive;

    /**
     * List of legal states that should be filtered out if cleanOutput is active.
     * 
     * @see LegalStatus
     * @since 1.2.6
     */
    @Parameter(property = "cleanOutputLegalStates", required = false)
    private LegalStatus[] cleanOutputLegalStates;

    /**
     * List of licenses that should be filtered out if cleanOutput is active, given by their SPDX identifier.
     * 
     * @since 1.2.6
     */
    @Parameter(property = "cleanOutputLicenseSpdxIdentifiers", required = false)
    private String[] cleanOutputLicenseSpdxIdentifiers;

    /**
     * If the Plug-in in case of an error should terminate with with a condition that lets the build fail.
     * 
     * @since 1.4.0
     */
    @Parameter(defaultValue = "false", property = "failOnError", required = false)
    private boolean failOnError;

    /**
     * List of legal states that should be considered an error.
     * 
     * The listed states lead to a build error if {@link #failOnError} is active.
     * 
     * @see LegalStatus
     * @since 1.4.0
     */
    @Parameter(property = "errorLegalStates", required = false)
    private LegalStatus[] errorLegalStates;

    /**
     * List of vendor names to filter out.
     * This is alternative to {@link #filteredVendorNamesFilename}. If both are given, the entries are merged.
     * 
     * @since 1.2.6
     */
    @Parameter(property = "filteredVendorNames", required = false)
    private List<String> filteredVendorNames;

    /**
     * Base URL for fetching Maven central artifacts from a server.
     * 
     * This can be Maven central itself (like the default value) or a mirror of maven central on a Nexus or other artifact server.
     * 
     * @since 1.2.6
     */
    @Parameter(defaultValue = "https://repo.maven.apache.org/maven2/", property = "nexusCentralBaseUrl", required = false)
    private String nexusCentralBaseUrl;

    /**
     * Timeout for connecting to artifact server. This timeout is used when
     * connecting to an artifact server (as configured with
     * {@link #nexusCentralBaseUrl}) to retrieve parent POMs. The value is in
     * milliseconds.
     * 
     * @since 1.3.1
     */
    @Parameter(defaultValue = "1000", property = "connectTimeout", required = false)
    private int connectTimeout;

    /**
     * Whether the license XML file should be validated while reading in.
     * 
     * @since 1.2.6
     */
    @Parameter(defaultValue = "false", property = "validateLicenseXml", required = false)
    private boolean validateLicenseXml;

    /**
     * Whether the documentation URL from the checked archives file should be displayed as a column in HTML and CSV reports.
     * 
     * @since 1.2.6
     */
    @Parameter(defaultValue = "true", property = "showDocumentationUrl", required = false)
    private boolean showDocumentationUrl;

    /**
     * Whether a skeleton archive XML file of all found archives should be written.
     * 
     * <p>If enabled, the file is written to {@link #archiveXmlSkeletonFile}.</p>
     * 
     * @see #archiveXmlSkeletonFile
     * 
     * @since 1.2.6
     */
    @Parameter(defaultValue = "false", property = "writeArchiveXmlSkeleton", required = false)
    private boolean writeArchiveXmlSkeleton;

    /**
     * File name a skeleton archive XML file of all found archives should be written to.
     * 
     * <p>Only used if {@link #writeArchiveXmlSkeleton} is true. </p>
     * 
     * @see #writeArchiveXmlSkeleton
     * 
     * @since 1.2.6
     */
    @Parameter(defaultValue = "archiveSkeleton.xml", property = "archiveXmlSkeletonFile", required = false)
    private File archiveXmlSkeletonFile;

    /**
     * Whether a skeleton archive CSV file of all found archives should be written.
     * 
     * <p>If enabled, the file is written to {@link #archiveXmlSkeletonFile}.</p>
     * 
     * @see #archiveCsvSkeletonFile
     * @since 1.2.6
     */
    @Parameter(defaultValue = "false", property = "writeArchiveCsvSkeleton", required = false)
    private boolean writeArchiveCsvSkeleton;

    /**
     * File name a skeleton archive CSV file of all found archives should be written to.
     * 
     * <p>Only used if {@link #writeArchiveCsvSkeleton} is true. </p>
     * 
     * @see #writeArchiveCsvSkeleton
     * @since 1.2.6
     */
    @Parameter(defaultValue = "archiveSkeleton.csv", property = "archiveCsvSkeletonFile", required = false)
    private File archiveCsvSkeletonFile;

    /**
     * The name of the build to use when writing database entries.
     * 
     * <p>Only used if {@link #writeResultsToDatabase} is true. </p>
     * 
     * @since 1.2
     */
    @Parameter(property = "buildName", required = false, defaultValue = "${project.artifactId}")
    private String buildName;

    /**
     * The version of the build to use when writing database entries.
     * 
     * <p>Only used if {@link #writeResultsToDatabase} is true.</p>
     * 
     * @since 1.2.6
     */
    @Parameter(property = "buildVersion", required = false, defaultValue = "${project.version}")
    private String buildVersion;

    /**
     * The URL of the build itself (point to Jenkins).
     * 
     * <p>Only used if {@link #writeResultsToDatabase} is true.</p>
     * 
     * @since 1.2.6
     */
    @Parameter(property = "buildUrl", required = false)
    private String buildUrl;

    /**
     * Whether the resulting reports should be written to a database.
     * 
     * <p>If enabled, the reports are written to the database configured with {@link #resultDatabaseConfiguration}.</p>
     * 
     * @see #resultDatabaseConfiguration
     * @since 1.2.6
     */
    @Parameter(defaultValue = "false", property = "writeResultsToDatabase", required = false)
    private boolean writeResultsToDatabase;

    /**
     * Whether the resulting reports should be written to a database in case the build is a snapshot
     * (identified by the value of {@link #buildVersion} ending with "-SNAPSHOT").
     * 
     * <p>This setting only has an effect if {@link #writeResultsToDatabase} is enabled.</p>
     * 
     * @see #writeResultsToDatabase
     * @see #resultDatabaseConfiguration
     * @since 1.2.6
     */
    @Parameter(defaultValue = "false", property = "writeResultsToDatabaseForSnapshotBuilds", required = false)
    private boolean writeResultsToDatabaseForSnapshotBuilds;

    /**
     * Database configuration for the database the reports should be written to.
     * 
     * <p>Only used if {@link #writeResultsToDatabase} is true. </p>
     * 
     * @see #writeResultsToDatabase
     * @see DatabaseConfiguration
     * @since 1.2.6
     */
    @Parameter(property = "resultDatabaseConfiguration", required = false)
    private DatabaseConfiguration resultDatabaseConfiguration;

    /**
     * Skips the execution.
     * 
     * @since 1.3.1
     */
    @Parameter(defaultValue = "false", property = "skip", required = false)
    private boolean skip;

    /**
     * Attach generated reports as files to the main artifact.
     * @since 1.3.1
     */
    @Parameter(defaultValue = "true", property = "attachReports", required = false)
    private boolean attachReports;

    /**
     * The Maven Project model.
     * @since 1.3.1
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject mavenProject;

    /**
     * The Maven project helper.
     */
    @Component
    private MavenProjectHelper mavenProjectHelper;

    /**
     * Artifact to use as configuration bundle.
     * @since 1.3.1
     */
    @Parameter(property = "configurationBundle", required = false)
    private ArtifactItem configurationBundle;

    /**
     * The project's remote repositories to use for the resolution.
     * @since 1.3.1
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepositories;

    /**
     * The Maven repository system.
     * @since 1.3.1
     */
    @Component
    private RepositorySystem repositorySystem;

    /**
     * The current repository/network configuration of Maven.
     * @since 1.3.1
     */
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repositorySystemSession;

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoExecutionException {
        final ILFLog log = new MavenLog(getLog());

        if (skip) {
            log.info("Not executing because skip is configured as true.");
            return;
        }
        File configurationBundleFile = null;
        if (configurationBundle != null && !StringUtils.isEmpty(configurationBundle.getArtifactId())) {
            configurationBundleFile = ArtifactHelper.getArtifactFile(this, configurationBundle);
        }

        final ExecutionParameters executionParameters = new ExecutionParameters();
        final ConfigFileParameters configFileParameters = new ConfigFileParameters();
        try {
            BeanUtils.copyProperties(executionParameters, this);
            BeanUtils.copyProperties(configFileParameters, this);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new MojoExecutionException("Internal error occured: " + e.getLocalizedMessage(), e);
        }
        executionParameters.setArchiveType(getArchiveType());
        executionParameters.setLsLog(log);
        executionParameters.setExporterFactories(Arrays.asList(new StandardReportExporterFactory()));

        final ConfigFileHandler configFileHandler = createConfigFileHandler(configurationBundleFile,
                configFileParameters, log);

        final Executor executor = new Executor(executionParameters, configFileHandler);
        try {
            executor.execute();
        } catch (LicenseScoutExecutionException e) {
            throw new MojoExecutionException("Internal error occured: " + e.getLocalizedMessage(), e);
        } catch (LicenseScoutFailOnErrorException e) {
            throw new MojoExecutionException("Fail on error condition: " + e.getLocalizedMessage(), e);
        }

        attachReports(executionParameters, log);
    }

    /**
     * @param configurationBundleFile
     * @param configFileParameters
     * @param log
     * @return a configuration file handler
     */
    private ConfigFileHandler createConfigFileHandler(final File configurationBundleFile,
                                                      final ConfigFileParameters configFileParameters,
                                                      final ILFLog log) {
        final ConfigFileHandler configFileHandler;
        if (configurationBundleFile != null) {
            configFileHandler = new ZipConfigFileHandler(configurationBundleFile, configFileParameters, log);
            log.info("reading configuration files from ZIP file: " + configurationBundleFile.getAbsolutePath());
        } else {
            configFileHandler = new FilesystemConfigFileHandler(configFileParameters, log);
            log.info("reading configuration files from file system");
        }
        return configFileHandler;
    }

    /**
     * @param executionParameters
     * @param log
     */
    private void attachReports(final ExecutionParameters executionParameters, final ILFLog log) {
        if (attachReports) {
            AttachHelper.attachReports(mavenProject, mavenProjectHelper, executionParameters);
        } else {
            log.info("Not attaching license reports as artifacts because not cnfigured");
        }
    }

    /**
     * Obtains the archive type handled by this MOJO.
     * 
     * @return an archive type
     */
    protected abstract ArchiveType getArchiveType();

    // --------------getters -- NOTE: these are called by reflection from BeanUtils.copyProperties()

    /**
     * @return the showDocumentationUrl
     */
    public final boolean isShowDocumentationUrl() {
        return showDocumentationUrl;
    }

    /**
     * @return the scanDirectory
     */
    public final File getScanDirectory() {
        return scanDirectory;
    }

    /**
     * @return the outputDirectory
     */
    public final String getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * @return the outputs
     */
    public final List<Output> getOutputs() {
        return outputs;
    }

    /**
     * @return the licensesFilename
     */
    public final File getLicensesFilename() {
        return licensesFilename;
    }

    /**
     * @return the providersFilename
     */
    public final File getProvidersFilename() {
        return providersFilename;
    }

    /**
     * @return the noticesFilename
     */
    public final File getNoticesFilename() {
        return noticesFilename;
    }

    /**
     * @return the checkedArchivesFilename
     */
    public final File getCheckedArchivesFilename() {
        return checkedArchivesFilename;
    }

    /**
     * @return the licenseUrlMappingsFilename
     */
    public final String getLicenseUrlMappingsFilename() {
        return licenseUrlMappingsFilename;
    }

    /**
     * @return the licenseNameMappingsFilename
     */
    public final String getLicenseNameMappingsFilename() {
        return licenseNameMappingsFilename;
    }

    /**
     * @return the globalFiltersFilename
     */
    public final String getGlobalFiltersFilename() {
        return globalFiltersFilename;
    }

    /**
     * @return the filteredVendorNamesFilename
     */
    public final String getFilteredVendorNamesFilename() {
        return filteredVendorNamesFilename;
    }

    /**
     * @return the cleanOutputActive
     */
    public final boolean isCleanOutputActive() {
        return cleanOutputActive;
    }

    /**
     * @return the cleanOutputLegalStates
     */
    public final LegalStatus[] getCleanOutputLegalStates() {
        return cleanOutputLegalStates;
    }

    /**
     * @return the cleanOutputLicenseSpdxIdentifiers
     */
    public final String[] getCleanOutputLicenseSpdxIdentifiers() {
        return cleanOutputLicenseSpdxIdentifiers;
    }

    /**
     * @return the failOnError
     */
    public final boolean isFailOnError() {
        return failOnError;
    }

    /**
     * @return the errorLegalStates
     */
    public final LegalStatus[] getErrorLegalStates() {
        return errorLegalStates;
    }

    /**
     * @return the skip
     */
    public final boolean isSkip() {
        return skip;
    }

    /**
     * @return the mavenProject
     */
    public final MavenProject getMavenProject() {
        return mavenProject;
    }

    /**
     * @return the mavenProjectHelper
     */
    public final MavenProjectHelper getMavenProjectHelper() {
        return mavenProjectHelper;
    }

    /**
     * @return the filteredVendorNames
     */
    public final List<String> getFilteredVendorNames() {
        return filteredVendorNames;
    }

    /**
     * @return the nexusCentralBaseUrl
     */
    public final String getNexusCentralBaseUrl() {
        return nexusCentralBaseUrl;
    }

    /**
     * @return the connectTimeout
     */
    public final int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * @return the validateLicenseXml
     */
    public final boolean isValidateLicenseXml() {
        return validateLicenseXml;
    }

    /**
     * @return the writeArchiveXmlSkeleton
     */
    public final boolean isWriteArchiveXmlSkeleton() {
        return writeArchiveXmlSkeleton;
    }

    /**
     * @return the archiveXmlSkeletonFile
     */
    public final File getArchiveXmlSkeletonFile() {
        return archiveXmlSkeletonFile;
    }

    /**
     * @return the writeArchiveCsvSkeleton
     */
    public final boolean isWriteArchiveCsvSkeleton() {
        return writeArchiveCsvSkeleton;
    }

    /**
     * @return the archiveCsvSkeletonFile
     */
    public final File getArchiveCsvSkeletonFile() {
        return archiveCsvSkeletonFile;
    }

    /**
     * @return the buildName
     */
    public final String getBuildName() {
        return buildName;
    }

    /**
     * @return the buildVersion
     */
    public final String getBuildVersion() {
        return buildVersion;
    }

    /**
     * @return the buildUrl
     */
    public final String getBuildUrl() {
        return buildUrl;
    }

    /**
     * @return the writeResultsToDatabase
     */
    public final boolean isWriteResultsToDatabase() {
        return writeResultsToDatabase;
    }

    /**
     * @return the writeResultsToDatabaseForSnapshotBuilds
     */
    public final boolean isWriteResultsToDatabaseForSnapshotBuilds() {
        return writeResultsToDatabaseForSnapshotBuilds;
    }

    /**
     * @return the resultDatabaseConfiguration
     */
    public final DatabaseConfiguration getResultDatabaseConfiguration() {
        return resultDatabaseConfiguration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final List<RemoteRepository> getRemoteRepositories() {
        return remoteRepositories;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final RepositorySystem getRepositorySystem() {
        return repositorySystem;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final RepositorySystemSession getRepositorySystemSession() {
        return repositorySystemSession;
    }

}