package com.pm.stack;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ecs.*;
import software.amazon.awscdk.services.ecs.Protocol;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.elasticache.CfnCacheCluster;
import software.amazon.awscdk.services.elasticache.CfnSubnetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.msk.CfnCluster;
import software.amazon.awscdk.services.rds.*;
import software.amazon.awscdk.services.route53.CfnHealthCheck;
import software.amazon.awscdk.services.servicediscovery.DnsRecordType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AwsStack extends Stack {
    private final Vpc vpc;
    private final Cluster ecsCluster;
    private final CfnCacheCluster elasticCacheCluster;
    private final CfnCluster mskCluster;

    private final CfnParameter ecrRepoUriPrefix;
    private final CfnParameter imageTag;

    private final SecurityGroup ecsSecurityGroup;
    private final SecurityGroup redisSecurityGroup;


    public AwsStack(final App scope, final String id, final StackProps props) {
        super(scope, id, props);

        this.ecrRepoUriPrefix = CfnParameter.Builder.create(this, "EcrRepoUriPrefix")
                .type("String")
                .description("The prefix of the ECR repository URI (e.g., 123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/patient-management)")
                .build();

        this.imageTag = CfnParameter.Builder.create(this, "ImageTag")
                .type("String")
                .description("The tag of the image to deploy")
                .defaultValue("latest")
                .build();

        this.vpc = createVpc();

        this.ecsSecurityGroup = SecurityGroup.Builder.create(this, "EcsSecurityGroup")
                .vpc(vpc)
                .description("Security group for ECS Fargate services")
                .build();

        // 2. Tạo SG cho Redis
        this.redisSecurityGroup = SecurityGroup.Builder.create(this, "RedisSecurityGroup")
                .vpc(vpc)
                .description("Security group for ElastiCache Redis cluster")
                .build();

        // 3. Thêm rule: cho phép ecsSecurityGroup truy cập redisSecurityGroup trên cổng 6379 (cổng mặc định của Redis)
        redisSecurityGroup.addIngressRule(
                Peer.securityGroupId(ecsSecurityGroup.getSecurityGroupId()),
                Port.tcp(6379),
                "Allow access from ECS services"
        );

        DatabaseInstance authServiceDb = createDatabase("AuthServiceDB", "auth-service-db");
        DatabaseInstance patientServiceDb = createDatabase("PatientServiceDB", "patient-service-db");

        CfnHealthCheck authDbHealthCheck = createDbHealthCheck(authServiceDb, "AuthServiceDBHealthCheck");
        CfnHealthCheck patientDbHealthCheck = createDbHealthCheck(patientServiceDb, "PatientServiceDBHealthCheck");

        this.mskCluster = createMskCluster(); // Khởi tạo mskCluster

        this.ecsCluster = createEcsCluster();
        this.elasticCacheCluster = createRedisCluster();

        FargateService authService = createFargateService("AuthService",
                "auth-service",
                List.of(4005),
                authServiceDb,
                Map.of("JWT_SECRET", "N2Y0ZDI0Y2E3NTM1YzU3ZjI2NzY0NzRjMzEwYzYzYWI="));

        authService.getNode().addDependency(authDbHealthCheck);
        authService.getNode().addDependency(authServiceDb);

        FargateService billingService = createFargateService("BillingService",
                "billing-service",
                List.of(4001, 9001),
                null,
                null);

        FargateService analyticsService = createFargateService("AnalyticsService",
                "analytics-service",
                List.of(4002),
                null,
                null);
        analyticsService.getNode().addDependency(mskCluster);

        FargateService patientService = createFargateService("PatientService",
                "patient-service",
                List.of(4000),
                patientServiceDb,
                Map.of(
                        "BILLING_SERVICE_ADDRESS", "billing-service.patient-management.local",
                        "BILLING_SERVICE_GRPC_PORT", "9001"
                )
        );
        patientService.getNode().addDependency(patientServiceDb);
        patientService.getNode().addDependency(patientDbHealthCheck);
        patientService.getNode().addDependency(billingService);
        patientService.getNode().addDependency(mskCluster);
        patientService.getNode().addDependency(elasticCacheCluster);

        ApplicationLoadBalancedFargateService apiGateway = createApiGatewayService();
        apiGateway.getNode().addDependency(elasticCacheCluster);

        FargateService prometheusService = createFargateService("PrometheusService",
                "prometheus-prod",
                List.of(9090),
                null,
                null
        );

        createGrafanaService();


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
                // Đề xuất: Dùng phiên bản LTS (Long-term support) ổn định hơn
                .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder().version(PostgresEngineVersion.VER_16_3).build()))
                .vpc(vpc)
                // Đề xuất: t3.micro là thế hệ mới hơn và hiệu năng tốt hơn t2
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .allocatedStorage(20)
                .credentials(Credentials.fromGeneratedSecret("admin_user"))
                .databaseName(dbName)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();
    }

    private CfnHealthCheck createDbHealthCheck(DatabaseInstance db, String id) {
        return CfnHealthCheck.Builder
                .create(this, id)
                .healthCheckConfig(CfnHealthCheck.HealthCheckConfigProperty.builder()
                        .type("TCP")
                        .port(Token.asNumber(db.getDbInstanceEndpointPort()))
                        .ipAddress(db.getDbInstanceEndpointAddress())
                        .requestInterval(30)
                        .failureThreshold(3)
                        .build())
                .build();
    }

    private CfnCluster createMskCluster() {
        return CfnCluster.Builder
                .create(this, "MskCluster")
                .clusterName("kafka-cluster")
                .kafkaVersion("2.8.1") // Dùng phiên bản được hỗ trợ rộng rãi
                .numberOfBrokerNodes(2) // Production nên dùng ít nhất 2 broker ở 2 AZ khác nhau
                .brokerNodeGroupInfo(CfnCluster.BrokerNodeGroupInfoProperty.builder()
                        .instanceType("kafka.m5.large") // Đây là kích thước tối thiểu AWS MSK yêu cầu
                        .clientSubnets(vpc.getPrivateSubnets().stream().map(ISubnet::getSubnetId).collect(Collectors.toList()))
                        .storageInfo(CfnCluster.StorageInfoProperty.builder()
                                .ebsStorageInfo(CfnCluster.EBSStorageInfoProperty.builder()
                                        .volumeSize(100) // Thêm dung lượng lưu trữ cho broker
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private Cluster createEcsCluster() {
        return Cluster.Builder
                .create(this, "PatientManagementCluster")
                .vpc(vpc)
                .defaultCloudMapNamespace(CloudMapNamespaceOptions.builder()
                        .name("patient-management.local")
                        .build())
                .build();
    }

    private FargateService createFargateService(String id, String imageName, List<Integer> ports,
                                                DatabaseInstance db, Map<String, String> additionalEnvVars) {
        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder
                .create(this, id + "Task")
                .cpu(256)
                .memoryLimitMiB(512)
                .build();

        String fullImageName = ecrRepoUriPrefix.getValueAsString() + "/" + imageName + ":" + imageTag.getValueAsString();

        ContainerDefinitionOptions.Builder containerOptions = ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry(fullImageName))
                .portMappings(ports.stream()
                        .map(port -> PortMapping.builder()
                                .containerPort(port)
                                .hostPort(port)
                                .protocol(Protocol.TCP)
                                .build())
                        .toList())
                .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                        .logGroup(LogGroup.Builder
                                .create(this, id + "LogGroup")
                                .logGroupName("/ecs/" + imageName)
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.ONE_DAY)
                                .build())
                        .streamPrefix(imageName)
                        .build()));

        Map<String, String> envVars = new HashMap<>();

        // Lấy bootstrap server động từ MSK cluster thay vì hardcode
        envVars.put("SPRING_KAFKA_BOOTSTRAP_SERVERS", mskCluster.getAtt("BootstrapBrokers").toString());
        envVars.put("SPRING_CACHE_TYPE", "redis");
        envVars.put("SPRING_DATA_REDIS_HOST", elasticCacheCluster.getAttrRedisEndpointAddress());
        envVars.put("SPRING_DATA_REDIS_PORT", elasticCacheCluster.getAttrRedisEndpointPort());

        if (additionalEnvVars != null) {
            envVars.putAll(additionalEnvVars);
        }

        if (db != null) {
            envVars.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://%s:%s/%s".formatted(
                    db.getDbInstanceEndpointAddress(), db.getDbInstanceEndpointPort(), imageName
            ));

            // Lấy username và password từ Secret Manager một cách an toàn
            envVars.put("SPRING_DATASOURCE_USERNAME", "admin_user");
            envVars.put("SPRING_DATASOURCE_PASSWORD", db.getSecret().secretValueFromJson("password").toString());

            envVars.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "update");
            envVars.put("SPRING_SQL_INIT_MODE", "always");
            envVars.put("SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT", "60000");
        }

        containerOptions.environment(envVars);
        taskDefinition.addContainer(imageName + "Container", containerOptions.build());

        return FargateService.Builder.create(this, id)
                .cluster(ecsCluster)
                .taskDefinition(taskDefinition)
                .assignPublicIp(false)
                .cloudMapOptions(CloudMapOptions.builder()
                        .name(imageName)
                        .dnsRecordType(DnsRecordType.A)
                        .build())
                .serviceName(imageName)
                .securityGroups(List.of(this.ecsSecurityGroup))
                .build();
    }

    private ApplicationLoadBalancedFargateService createApiGatewayService() {
        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder
                .create(this,  "ApiGatewayTaskDefinition")
                .cpu(256)
                .memoryLimitMiB(512)
                .build();

        String fullImageName = ecrRepoUriPrefix.getValueAsString() + "/api-gateway:" + imageTag.getValueAsString();

        ContainerDefinitionOptions containerOptions = ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry(fullImageName))
                .environment(Map.of(
                        "SPRING_PROFILES_ACTIVE", "prod",
                        "AUTH_SERVICE_URL", "http://auth-service.patient-management.local:4005",
                        "REDIS_HOST", elasticCacheCluster.getAttrRedisEndpointAddress(),
                        "REDIS_PORT", elasticCacheCluster.getAttrRedisEndpointPort()
                ))
                .portMappings(List.of(PortMapping.builder().containerPort(4004).protocol(Protocol.TCP).build()))
                .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                        .logGroup(LogGroup.Builder
                                .create(this, "ApiGatewayLogGroup")
                                .logGroupName("/ecs/api-gateway")
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .retention(RetentionDays.ONE_DAY)
                                .build())
                        .streamPrefix("api-gateway")
                        .build()))
                .build();

        taskDefinition.addContainer("APIGatewayContainer", containerOptions);

        return ApplicationLoadBalancedFargateService.Builder
                .create(this, "APIGatewayService")
                .cluster(ecsCluster)
                .serviceName("api-gateway")
                .taskDefinition(taskDefinition)
                .desiredCount(1)
                .healthCheckGracePeriod(Duration.seconds(120)) // Tăng thời gian chờ cho Spring Boot khởi động
                .publicLoadBalancer(true)
                .cloudMapOptions(CloudMapOptions.builder()
                        .name("api-gateway")
                        .dnsRecordType(DnsRecordType.A)
                        .build())
                .build();
    }

    private CfnCacheCluster createRedisCluster() {
        CfnSubnetGroup redisSubnetGroup = CfnSubnetGroup.Builder
                .create(this, "RedisSubnetGroup")
                .description("Redis/elasticache subnet group")
                .subnetIds(vpc.getPrivateSubnets().stream()
                        .map(ISubnet::getSubnetId)
                        .collect(Collectors.toList()))
                .build();

        return CfnCacheCluster.Builder.create(this, "RedisCluster")
                .cacheNodeType("cache.t3.micro") // t3 thế hệ mới
                .engine("redis")
                .numCacheNodes(1)
                .cacheSubnetGroupName(redisSubnetGroup.getRef())
                .vpcSecurityGroupIds(List.of(redisSecurityGroup.getSecurityGroupId()))
                .build();
    }

    private ApplicationLoadBalancedFargateService createGrafanaService() {
        FargateTaskDefinition taskDefinition = FargateTaskDefinition.Builder
                .create(this,  "GrafanaService")
                .cpu(256)
                .memoryLimitMiB(512)
                .build();

        taskDefinition.addContainer("GrafanaContainer", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromRegistry("grafana/grafana-oss")) // Dùng bản Open Source
                .portMappings(List.of(PortMapping.builder().containerPort(3000).build()))
                .build());

        return ApplicationLoadBalancedFargateService.Builder
                .create(this, "GrafanaUIService")
                .cluster(ecsCluster)
                .taskDefinition(taskDefinition)
                .publicLoadBalancer(true)
                .listenerPort(3000)
                .desiredCount(1)
                .build();
    }

    public static void main(final String[] args) {
        App app = new App();
        new AwsStack(app, "PatientManagementStack", StackProps.builder()
                .build());
        app.synth();
    }
}

