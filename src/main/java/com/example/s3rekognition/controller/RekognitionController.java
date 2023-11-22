package com.example.s3rekognition.controller;

import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.example.s3rekognition.PPEClassificationResponse;
import com.example.s3rekognition.PPEResponse;
import com.example.s3rekognition.TextRekognition;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;


@RestController
public class RekognitionController implements ApplicationListener<ApplicationReadyEvent> {

    private final AmazonS3 s3Client;
    private final AmazonRekognition rekognitionClient;
    private final TextRekognition textRekognition;
    private final MeterRegistry meterRegistry;

    private static int totalPPEScan = 15;
    private static int totalTextScan = 17;
    private static final Logger logger = Logger.getLogger(RekognitionController.class.getName());
    private static int counter = 0;

    @Autowired
    public RekognitionController(AmazonS3 s3Client, AmazonRekognition rekognitionClient, 
                                 TextRekognition textRekognition, MeterRegistry meterRegistry) {
        this.s3Client = s3Client;
        this.rekognitionClient = rekognitionClient;
        this.textRekognition = textRekognition;
        this.meterRegistry = meterRegistry;
    }
    
    
    @GetMapping("/")
    public ResponseEntity<Object> helloWorld() {
        logger.info("Hello world " + counter++);
        return new ResponseEntity<>("Hello World", HttpStatus.OK);
    }

    /**
     * This endpoint takes an S3 bucket name in as an argument, scans all the
     * Files in the bucket for Protective Gear Violations.
     * <p>
     *
     * @param bucketName bucket name
     * @return json
     */
    @GetMapping(value = "/scan-ppe", consumes = "*/*", produces = "application/json")
    @ResponseBody
    public ResponseEntity<PPEResponse> scanForPPE(@RequestParam String bucketName) {
        // List all objects in the S3 bucket
        ListObjectsV2Result imageList = s3Client.listObjectsV2(bucketName);

        // This will hold all of our classifications
        List<PPEClassificationResponse> classificationResponses = new ArrayList<>();

        // This is all the images in the bucket
        List<S3ObjectSummary> images = imageList.getObjectSummaries();

        // Iterate over each object and scan for PPE
        final float MIN_CONFIDENCE = 80f;
        for (S3ObjectSummary image : images) {
            logger.info("scanning " + image.getKey());

            // This is where the magic happens, use AWS rekognition to detect PPE
            DetectProtectiveEquipmentRequest request = new DetectProtectiveEquipmentRequest()
                    .withImage(new Image()
                            .withS3Object(new S3Object()
                                    .withBucket(bucketName)
                                    .withName(image.getKey())))
                    .withSummarizationAttributes(new ProtectiveEquipmentSummarizationAttributes()
                            .withMinConfidence(MIN_CONFIDENCE)
                            .withRequiredEquipmentTypes("FACE_COVER"));

            DetectProtectiveEquipmentResult result = rekognitionClient.detectProtectiveEquipment(request);

            // If any person on an image lacks PPE on the face, it's a violation of regulations
            boolean violation = isViolation(result);

            logger.info("scanning " + image.getKey() + ", violation result " + violation);
            // Categorize the current image as a violation or not.
            int personCount = result.getPersons().size();
            PPEClassificationResponse classification = new PPEClassificationResponse(image.getKey(), personCount, violation);
            classificationResponses.add(classification);
            totalPPEScan++;
        }
        PPEResponse ppeResponse = new PPEResponse(bucketName, classificationResponses);
        return ResponseEntity.ok(ppeResponse);
    }

    /**
     * Detects if the image has a protective gear violation for the FACE bodypart-
     * It does so by iterating over all persons in a picture, and then again over
     * each body part of the person. If the body part is a FACE and there is no
     * protective gear on it, a violation is recorded for the picture.
     *
     * @param result result
     * @return string
     */
    private static boolean isViolation(DetectProtectiveEquipmentResult result) {
        return result.getPersons().stream()
                .flatMap(p -> p.getBodyParts().stream())
                .anyMatch(bodyPart -> bodyPart.getName().equals("FACE")
                        && bodyPart.getEquipmentDetections().isEmpty());
    }

    /**
     * Takes in pictures from POST and detects what Text is in them
     *
     * @param files sent inn as HTTP POST multipart/form-data
     * @return String of response from AWS Rekognition
     */
    @PostMapping("/scan-text")
    public ResponseEntity<Object> scanTextOnImage(@RequestParam("file") MultipartFile[] files) {
        if (files.length == 0) return new ResponseEntity<>("No file received", HttpStatus.BAD_REQUEST);
        logger.info(String.format("Scanning %d files", files.length));
        
        StringBuilder response = new StringBuilder();
        try {
            for (MultipartFile multipartFile : files) {
                logger.info("Scanning file " + multipartFile.getName());
                response.append(textRekognition.detectTextLabels(multipartFile.getInputStream()));
                totalTextScan++;
            }
        } catch (Exception e) {
            return new ResponseEntity<>("Error from AWS Rekognition", HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(response.toString(), HttpStatus.OK);
    }

    /**
     * Takes images from resources/images and send them to AWS rekognition
     * @param id needs to be between 1-4
     * @return String of response from AWS Rekognition
     */
    @GetMapping("/scan-text-backup/{id}")
    public ResponseEntity<Object> scanTextOnImageBackup(@PathVariable int id) {
        if (id < 1 || id > 4) return new ResponseEntity<>("ID outside range", HttpStatus.BAD_REQUEST);
        
        String response; 
        try {
            response = textRekognition.detectTextLabels(ClassLoader.getSystemResourceAsStream("images/img" + id + ".jpg"));
            totalPPEScan++;
        } catch (Exception e) {
            return new ResponseEntity<>("Error from AWS Rekognition", HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(response, HttpStatus.OK);
    }


    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
        Gauge.builder("PPE_scan_count", totalPPEScan, s -> s).register(meterRegistry);
        Gauge.builder("Text_scan_count", totalTextScan, s -> s).register(meterRegistry);
    }
}
