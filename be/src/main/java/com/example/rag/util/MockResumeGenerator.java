package com.example.rag.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates mock resume PDFs for testing. Run main() to create 20 PDFs in the given output directory.
 * <p>
 * Run from project root: ./gradlew :be:run -PmockResumesOutput=be/src/main/resources/resumes
 * Or from be: ./gradlew run -PmockResumesOutput=src/main/resources/resumes
 */
public final class MockResumeGenerator {

    private static final float FONT_SIZE_TITLE = 16;
    private static final float FONT_SIZE_BODY = 11;
    private static final float MARGIN = 50;
    private static final float LINE_HEIGHT = 14;

    public static void main(String[] args) throws IOException {
        String outDir = args.length > 0 ? args[0] : System.getProperty("mockResumesOutput", "src/main/resources/resumes");
        Path outputPath = Path.of(outDir).toAbsolutePath();
        Files.createDirectories(outputPath);
        System.out.println("Generating mock resumes in: " + outputPath);

        int n = 0;
        n += generateJavaSpring(outputPath);
        n += generateDotNetAzure(outputPath);
        n += generateDevOpsCloud(outputPath);
        n += generatePython(outputPath);
        n += generateMachineLearning(outputPath);

        System.out.println("Created " + n + " mock resume PDFs.");
    }

    private static int generateJavaSpring(Path out) throws IOException {
        List<String[]> resumes = List.of(
                new String[]{"John Smith", "Senior Java Developer", "Java, Spring Boot, Spring Cloud, REST APIs, Maven, JUnit, PostgreSQL. 5+ years building enterprise applications."},
                new String[]{"Maria Garcia", "Java Backend Engineer", "Java 17, Spring Framework, Spring Security, Microservices, Kafka, Docker. Experience with Agile and CI/CD."},
                new String[]{"David Chen", "Full Stack Java Developer", "Java, Spring MVC, Spring Data, Hibernate, React, AWS. Led team of 4 developers on billing platform."},
                new String[]{"Emily Johnson", "Software Engineer - Java", "Java, Spring Boot, Gradle, Redis, Elasticsearch. Built high-throughput payment processing services."}
        );
        for (int i = 0; i < resumes.size(); i++) {
            writePdf(out.resolve("Resume_JavaSpring_" + (i + 1) + "_" + resumes.get(i)[0].replace(" ", "_") + ".pdf"), resumes.get(i));
        }
        return resumes.size();
    }

    private static int generateDotNetAzure(Path out) throws IOException {
        List<String[]> resumes = List.of(
                new String[]{"Michael Brown", ".NET Solutions Architect", "C#, .NET 8, ASP.NET Core, Azure DevOps, Azure Functions, SQL Server, Entity Framework. 8 years experience."},
                new String[]{"Sarah Williams", "Senior .NET Developer", ".NET Core, Azure App Service, Azure SQL, Blazor, REST, Microservices. Certified Azure Developer."},
                new String[]{"James Wilson", "Full Stack .NET Engineer", "C#, .NET, Azure, React, Cosmos DB, Service Bus. Built scalable SaaS product on Azure."},
                new String[]{"Lisa Anderson", "Azure Cloud Developer", ".NET, Azure PaaS, Azure AD, Key Vault, ARM templates, CI/CD with Azure Pipelines. 6 years cloud experience."}
        );
        for (int i = 0; i < resumes.size(); i++) {
            writePdf(out.resolve("Resume_DotNet_Azure_" + (i + 1) + "_" + resumes.get(i)[0].replace(" ", "_") + ".pdf"), resumes.get(i));
        }
        return resumes.size();
    }

    private static int generateDevOpsCloud(Path out) throws IOException {
        List<String[]> resumes = List.of(
                new String[]{"Robert Taylor", "DevOps Engineer", "AWS, Terraform, Kubernetes, Docker, Jenkins, Linux, Python scripting. Built multi-account AWS landing zone."},
                new String[]{"Jennifer Martinez", "Cloud Infrastructure Engineer", "GCP, Google Cloud Run, GKE, Cloud Build, IAM, BigQuery. Migrated on-prem workloads to GCP."},
                new String[]{"Christopher Lee", "Senior DevOps/SRE", "AWS, EKS, EC2, Lambda, CloudFormation, Prometheus, Grafana. 24/7 on-call and incident response."},
                new String[]{"Amanda White", "DevOps & Cloud Specialist", "AWS, GCP, Azure, Kubernetes, Helm, ArgoCD, GitOps. Implemented full CI/CD for 15 microservices."}
        );
        for (int i = 0; i < resumes.size(); i++) {
            writePdf(out.resolve("Resume_DevOps_Cloud_" + (i + 1) + "_" + resumes.get(i)[0].replace(" ", "_") + ".pdf"), resumes.get(i));
        }
        return resumes.size();
    }

    private static int generatePython(Path out) throws IOException {
        List<String[]> resumes = List.of(
                new String[]{"Daniel Harris", "Python Backend Developer", "Python, Django, FastAPI, PostgreSQL, Celery, Redis. Built APIs and data pipelines for fintech."},
                new String[]{"Jessica Clark", "Senior Python Engineer", "Python, Flask, SQLAlchemy, pytest, Docker, AWS. 5 years backend and data engineering."},
                new String[]{"Matthew Lewis", "Python Developer", "Python 3, Pandas, NumPy, Apache Airflow, ETL, REST APIs. Experience in data and automation."},
                new String[]{"Ashley Robinson", "Full Stack Python", "Python, Django, React, PostgreSQL, Heroku. Startup experience; built MVP and scaled to 10k users."}
        );
        for (int i = 0; i < resumes.size(); i++) {
            writePdf(out.resolve("Resume_Python_" + (i + 1) + "_" + resumes.get(i)[0].replace(" ", "_") + ".pdf"), resumes.get(i));
        }
        return resumes.size();
    }

    private static int generateMachineLearning(Path out) throws IOException {
        List<String[]> resumes = List.of(
                new String[]{"Andrew King", "ML Engineer", "Python, TensorFlow, PyTorch, scikit-learn, NLP, computer vision. Deployed models to production at scale."},
                new String[]{"Stephanie Wright", "Data Scientist / ML", "Machine learning, deep learning, Python, pandas, Jupyter, AWS SageMaker. Recommendation systems and A/B tests."},
                new String[]{"Kevin Scott", "Senior ML Engineer", "PyTorch, Transformers, LLMs, MLOps, Kubeflow, feature stores. Built search and ranking models."},
                new String[]{"Rachel Green", "Machine Learning Researcher", "PhD ML. Python, TensorFlow, research publications in NLP. Experience with BERT, GPT fine-tuning, RAG systems."}
        );
        for (int i = 0; i < resumes.size(); i++) {
            writePdf(out.resolve("Resume_ML_" + (i + 1) + "_" + resumes.get(i)[0].replace(" ", "_") + ".pdf"), resumes.get(i));
        }
        return resumes.size();
    }

    private static void writePdf(Path file, String[] content) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), FONT_SIZE_TITLE);
                cs.newLineAtOffset(MARGIN, y);
                cs.showText(content[0]);
                cs.endText();
                y -= LINE_HEIGHT * 1.5f;

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE_BODY);
                cs.newLineAtOffset(MARGIN, y);
                cs.showText(content[1]);
                cs.endText();
                y -= LINE_HEIGHT * 2;

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE_BODY);
                String body = content[2];
                float maxWidth = page.getMediaBox().getWidth() - 2 * MARGIN;
                for (String line : wrapText(body, (int) (maxWidth / (FONT_SIZE_BODY * 0.6f)))) {
                    cs.newLineAtOffset(MARGIN, y);
                    cs.showText(line);
                    y -= LINE_HEIGHT;
                    if (y < MARGIN) break;
                }
                cs.endText();
            }

            doc.save(file.toFile());
        }
    }

    private static String[] wrapText(String text, int maxCharsPerLine) {
        String[] words = text.split(" ");
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String w : words) {
            if (current.length() + w.length() + 1 > maxCharsPerLine && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append(" ");
            current.append(w);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines.toArray(String[]::new);
    }

    private MockResumeGenerator() {}
}
