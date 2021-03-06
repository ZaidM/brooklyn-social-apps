package io.cloudsoft.socialapps.wordpress.examples;

import io.cloudsoft.socialapps.wordpress.CustomNginxControllerImpl;
import io.cloudsoft.socialapps.wordpress.Wordpress;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.catalog.Catalog;
import brooklyn.config.BrooklynProperties;
import brooklyn.enricher.basic.SensorPropagatingEnricher;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.database.mysql.MySqlNode;
import brooklyn.entity.proxy.nginx.NginxController;
import brooklyn.entity.proxying.BasicEntitySpec;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.entity.proxying.EntityTypeRegistry;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.entity.webapp.DynamicWebAppCluster;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.event.basic.DependentConfiguration;
import brooklyn.launcher.BrooklynLauncher;
import brooklyn.policy.autoscaling.AutoScalerPolicy;
import brooklyn.util.CommandLineUtil;

import com.google.common.collect.Lists;

@Catalog(name="Clustered WordPress", 
        description="A WordPress cluster - the free and open source blogging tool and a content management system - with an nginx load balancer",
        iconUrl="http://www.wordpress.org/about/images/logos/wordpress-logo-notext-rgb.png")
public class ClusteredWordpressApp extends AbstractApplication {
    
    // TODO Currently only works on CentOS or RHEL
    
    public static final Logger log = LoggerFactory.getLogger(ClusteredWordpressApp.class);

    final static String SCRIPT = "create database wordpress; " +
            "grant all privileges on wordpress.* TO 'wordpress'@'localhost'  IDENTIFIED BY 'password'; " +
            "grant all privileges on wordpress.* TO 'wordpress'@'127.0.0.1'  IDENTIFIED BY 'password'; " +
            "grant all privileges on wordpress.* TO 'wordpress'@'%'  IDENTIFIED BY 'password';" +
            "flush privileges;";
    
    private MySqlNode mysql;
    private ControlledDynamicWebAppCluster cluster;

    @Override
    public void init() {
        EntityTypeRegistry typeRegistry = getManagementContext().getEntityManager().getEntityTypeRegistry();
        typeRegistry.registerImplementation(NginxController.class, CustomNginxControllerImpl.class);
        
        mysql = addChild(BasicEntitySpec.newInstance(MySqlNode.class)
                .configure("creationScriptContents", SCRIPT));

        cluster = addChild(BasicEntitySpec.newInstance(ControlledDynamicWebAppCluster.class)
                .configure(ControlledDynamicWebAppCluster.INITIAL_SIZE, 2)
                .configure(ControlledDynamicWebAppCluster.MEMBER_SPEC, BasicEntitySpec.newInstance(Wordpress.class)
                        .configure(Wordpress.DATABASE_UP, DependentConfiguration.attributeWhenReady(mysql, MySqlNode.SERVICE_UP))
                        .configure(Wordpress.DATABASE_HOSTNAME, DependentConfiguration.attributeWhenReady(mysql, MySqlNode.HOSTNAME))
                        .configure(Wordpress.DATABASE_NAME, "wordpress")
                        .configure(Wordpress.DATABASE_USER, "wordpress")
                        .configure(Wordpress.DATABASE_PASSWORD, "password")
                        .configure(Wordpress.WEBLOG_TITLE, "Welcome to WordPress, installed by Brooklyn!")
                        .configure(Wordpress.WEBLOG_ADMIN_EMAIL, BasicWordpressApp.EMAIL)
                        .configure(Wordpress.WEBLOG_ADMIN_PASSWORD, BasicWordpressApp.PASSWORD)
                        .configure(Wordpress.USE_W3_TOTAL_CACHE, true)
                        ));
                        
        cluster.getCluster().addPolicy(AutoScalerPolicy.builder()
                .metric(DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW_PER_NODE)
                .metricRange(10, 25)
                .sizeRange(2, 5)
                .build());

        SensorPropagatingEnricher.newInstanceListeningTo(cluster, WebAppService.ROOT_URL).addToEntityAndEmitAll(this);
    }

    public static void main(String[] argv) throws Exception {
        List<String> args = Lists.newArrayList(argv);
        String port =  CommandLineUtil.getCommandLineOption(args, "--port", "8081+");
        String location = CommandLineUtil.getCommandLineOption(args, "--location", null);

        BrooklynProperties brooklynProperties = BrooklynProperties.Factory.newDefault();

        // alex's choice: older centos probably, but a better way to set
        // (put this in brooklyn.properties and this location will be used)
//        brooklyn.location.named.aws-ec2-us-east-1-centos=jclouds:aws-ec2:us-east-1
//        brooklyn.location.named.aws-ec2-us-east-1-centos.imageId=us-east-1/ami-043f9c6d
//        brooklyn.location.named.aws-ec2-us-east-1-centos.minRam=4096
//        brooklyn.location.named.aws-ec2-us-east-1-centos.user=root
        if (location==null && brooklynProperties.containsKey("brooklyn.location.named.aws-ec2-us-east-1-centos")) {
            log.info("Using default location named:aws-ec2-us-east-1-centos because it is available");
            location = "named:aws-ec2-us-east-1-centos";
        }
  
        // aled's choice:
        // Image: {id=us-east-1/ami-7d7bfc14, providerId=ami-7d7bfc14, name=RightImage_CentOS_6.3_x64_v5.8.8.5, location={scope=REGION, id=us-east-1, description=us-east-1, parent=aws-ec2, iso3166Codes=[US-VA]}, os={family=centos, arch=paravirtual, version=6.0, description=rightscale-us-east/RightImage_CentOS_6.3_x64_v5.8.8.5.manifest.xml, is64Bit=true}, description=rightscale-us-east/RightImage_CentOS_6.3_x64_v5.8.8.5.manifest.xml, version=5.8.8.5, status=AVAILABLE[available], loginUser=root, userMetadata={owner=411009282317, rootDeviceType=instance-store, virtualizationType=paravirtual, hypervisor=xen}}
        // TODO Set for only us-east-1 region, rather than all aws-ec2
        if (location==null) {
            log.info("Using default CentOS image in default location AWS us-east-1");
            brooklynProperties.put("brooklyn.jclouds.aws-ec2.image-id", "us-east-1/ami-7d7bfc14");
            location = "aws-ec2:us-east-1";
        }

        BrooklynLauncher launcher = BrooklynLauncher.newInstance()
                .brooklynProperties(brooklynProperties)
                .application(EntitySpecs.appSpec(ClusteredWordpressApp.class)
                        .displayName("Clustered wordpress app"))
                .webconsolePort(port)
                .location(location)
                .start();

        Entities.dumpInfo(launcher.getApplications());
    }
}
