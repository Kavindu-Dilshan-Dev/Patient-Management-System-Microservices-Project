package com.kavindu.stack;


import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.route53.CfnHealthCheck;
import software.amazon.awscdk.services.msk.CfnCluster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class LocalStack extends Stack {

    private final Vpc vpc;
    private final Cluster ecsCluster;

    public LocalStack(final App scope, final String id, final StackProps props) {
        super(scope, id, props);
        this.vpc = createVpc();

        DatabaseInstance authServiceDb = createDatabase("AuthServiceDB","auth-service-db");
        DatabaseInstance patientServiceDb = createDatabase("patientServiceDB","patient-service-db");

        CfnHealthCheck authDbHealthCheck  =createDbHealthCheck(authServiceDb, "AuthServiceDBHealthCheck");
        CfnHealthCheck patientDbHealthCheck = createDbHealthCheck(patientServiceDb , "PatientServiceDBHealthCheck");

        CfnCluster mskCluster = createMskCluster();

        this.ecsCluster = createEcsCluster();

        FargateService authService = createFargateService
                (
                "AuthService",
                "auth-service",
                List.of(4005),
                        authServiceDb,
                        Map.of("JWT_SECRET","JWT_SECRET=324312fbb2b4d8df36e5b840c91cbc8c9eb57a31290312436c21fcab7161559e1f7746ee748db40bd41a5c31515b207958b7df8788004c439145fb2915df560018018ad05dad49938186d90b3b2b7664598f40e9ccf93a9ce02ac1f311f2b8f5e5d41551a8d91487897d14ba76d05e5b5e1324aef5de127aebdc20780d9320c4")
                );

        authService.getNode().addDependency(authDbHealthCheck);
        authService.getNode().addDependency(authServiceDb);

        FargateService billingService = createFargateService("BillingService","billing-service",List.of(4001,9001), null,null);

        FargateService analyticsService = createFargateService("AnalyticsService","analytics-service",List.of(4002), null,null);
        analyticsService.getNode().addDependency(mskCluster);

        FargateService patientService = createFargateService("PatientService",
                "patient-service",
                List.of(4000),
                patientServiceDb,
                Map.of(
                        "BILLING_SERVICE_ADDRESS","host.docker.internal",
                        "BILLING_SERVICE_GRPC_PORT","9001"
                ));
        patientService.getNode().addDependency(patientServiceDb);
        patientService.getNode().addDependency(patientDbHealthCheck);
        patientService.getNode().addDependency(billingService);
        patientService.getNode().addDependency(mskCluster);

        createApiGatewayService();

    }


    private Vpc createVpc() {
        return Vpc.Builder
                .create(this, "PatientManagementVPC")
                .vpcName("PatientManagementVPC")
                .maxAzs(2)
                .build();
    }

    private DatabaseInstance createDatabase(String id, String dbName) {
        return DatabaseInstance.Builder
                .create(this, id)
                .engine(DatabaseInstanceEngine.postgres(
                        PostgresInstanceEngineProps.builder()
                                .version(PostgresEngineVersion.VER_17_2)
                                .build()))
                .vpc(vpc)
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE2, InstanceSize.MICRO))
                .allocatedStorage(20)
                .credentials(Credentials.fromGeneratedSecret("admin_user"))
                .databaseName(dbName)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }

    private CfnHealthCheck createDbHealthCheck(DatabaseInstance db , String id) {
        return CfnHealthCheck.Builder.create(this, id)
                .healthCheckConfig(CfnHealthCheck.HealthCheckConfigProperty.builder()
                        .type("TCP")
                        .port(Token.asNumber(db.getDbInstanceEndpointAddress()))
                        .requestInterval(30)
                        .failureThreshold(3)
                        .build())
                .build();
    }

    private CfnCluster createMskCluster(){
        return CfnCluster.Builder.create(this, "MskCluster")
                .clusterName("kafka-cluster")
                .kafkaVersion("2.8.0")
                .numberOfBrokerNodes(1)
                .brokerNodeGroupInfo(CfnCluster.BrokerNodeGroupInfoProperty.builder()
                        .instanceType("kafka.m5.xlarge")
                        .clientSubnets(vpc.getPrivateSubnets().stream()
                                .map(ISubnet::getSubnetId)
                                .collect(Collectors.toList()))
                        .brokerAzDistribution("DEFAULT")
                        .build())
                .build();
    }

// ex:  auth-service.patient-management.local
   private Cluster createEcsCluster(){
        return Cluster.Builder.create(this , "PatientManagementCluster")
                .vpc(vpc)
                .defaultCloudMapNamespace(CloudMapNamespaceOptions.builder()
                        .name("patient-management.local")
                        .build())
                .build();
   }

   private FargateService createFargateService(
           String id ,
           String imageName,
           List<Integer> ports ,
           DatabaseInstance db,
           Map<String , String> additionalEnvVars
   ){
       FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder.create(this, id + "Task")
               .cpu(256)
               .memoryLimitMiB(512)
               .build();
       ContainerDefinitionOptions.Builder containerOptions =
                ContainerDefinitionOptions.builder()
                       .image(ContainerImage.fromRegistry(imageName))
                       .portMappings(ports.stream()
                               .map(port -> PortMapping.builder()
                                       .containerPort(port)
                                       .hostPort(port)
                                       .protocol(Protocol.TCP)
                                       .build())
                               .toList())
                       .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                                       .logGroup(LogGroup.Builder.create(this, id + "LogGroup")
                                               .logGroupName("/ecs/" + imageName)
                                               .removalPolicy(RemovalPolicy.DESTROY)
                                               .retention(RetentionDays.ONE_DAY)
                                               .build())
                                       .streamPrefix(imageName)
                               .build()));

       Map<String,String> envVars = new HashMap<>();
       envVars.put("SPRING_KAFKA_BOOTSTRAP_SERVERS","localhost.localstack.cloud:4510,localhost.localstack.cloud:4511,localhost.localstack.cloud:4512");

       if(additionalEnvVars!=null){
           envVars.putAll(additionalEnvVars);
       }
       if (db != null) {
           envVars.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://%s:%s/%s-db".formatted(
                   db.getDbInstanceEndpointAddress(),
                   db.getDbInstanceEndpointPort(),
                   imageName
           ));

           envVars.put("SPRING_DATASOURCE_USERNAME", "admin_user");
           envVars.put("SPRING_DATASOURCE_PASSWORD", db.getSecret().secretValueFromJson("password").toString());
           envVars.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "update");
           envVars.put("SPRING_SQL_INIT_MODE","always");
           envVars.put("SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT","60000");
       }

       containerOptions.environment(envVars);
       taskDefinition.addContainer(imageName + "Container" , containerOptions.build());

       return  FargateService.Builder.create(this,id)
               .cluster(ecsCluster)
               .taskDefinition(taskDefinition)
               .assignPublicIp(false)
               .serviceName(imageName)
               .build();
   }

   private void createApiGatewayService(){
       FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder.create(this,"APIGatewayTaskDefinition")
               .cpu(256)
               .memoryLimitMiB(512)
               .build();

       ContainerDefinitionOptions  containerOptions =
               ContainerDefinitionOptions.builder()
                       .image(ContainerImage.fromRegistry("api-gateway"))
                       .environment(Map.of(
                               "SPRING_PROFILES_ACTIVE","prod",
                               "AUTH_SERVICE_URL","http://host.docker.internal:4005"
                       ))
                       .portMappings(List.of(4004).stream()
                               .map(port -> PortMapping.builder()
                                       .containerPort(port)
                                       .hostPort(port)
                                       .protocol(Protocol.TCP)
                                       .build())
                               .toList())
                       .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                               .logGroup(LogGroup.Builder.create(this, "APIGatewayLogGroup")
                                       .logGroupName("/ecs/api-gateway")
                                       .removalPolicy(RemovalPolicy.DESTROY)
                                       .retention(RetentionDays.ONE_DAY)
                                       .build())
                               .streamPrefix("api-gateway")
                               .build()))
                       .build();

       taskDefinition.addContainer("APIGatewayContainer", containerOptions);

       ApplicationLoadBalancedFargateService apiGateway
               = ApplicationLoadBalancedFargateService.Builder.create(this,"APIGatewayService")
               .cluster(ecsCluster)
               .serviceName("api-gateway")
               .taskDefinition(taskDefinition)
               .desiredCount(1)
               .healthCheckGracePeriod(Duration.seconds(60))
               .build();



   }



    public static void main(final String[] args) {
        App app = new App(AppProps.builder().outdir("./cdk.out").build());

        StackProps props = StackProps.builder()
                .synthesizer(new BootstraplessSynthesizer())
                .build();

        new LocalStack(app, "localstack", props);
        app.synth();
        System.out.println("App Synthesizer in progress...");
    }
}
