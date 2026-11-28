-- MySQL dump 10.13  Distrib 8.4.13, for Linux (x86_64)
--
-- Host: localhost    Database: labops
-- ------------------------------------------------------
-- Server version	8.4.13

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `labops`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `labops` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `labops`;

--
-- Table structure for table `equipment`
--

DROP TABLE IF EXISTS `equipment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `equipment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `category` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `location` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `manufacturer` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `model` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `responsible_person` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `purchase_date` date DEFAULT NULL,
  `description` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `hourly_price_cents` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `equipment`
--

LOCK TABLES `equipment` WRITE;
/*!40000 ALTER TABLE `equipment` DISABLE KEYS */;
/*!40000 ALTER TABLE `equipment` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `equipment_reservations`
--

DROP TABLE IF EXISTS `equipment_reservations`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `equipment_reservations` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `equipment_id` bigint NOT NULL,
  `requester_id` bigint NOT NULL,
  `requester_name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `purpose` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `start_time` timestamp(6) NOT NULL,
  `end_time` timestamp(6) NOT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `expires_at` timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `version` bigint NOT NULL DEFAULT '0',
  `payment_deadline` timestamp(6) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_reservation_conflict` (`equipment_id`,`status`,`start_time`,`end_time`),
  KEY `fk_reservation_requester` (`requester_id`),
  KEY `idx_reservation_payment_deadline` (`status`,`payment_deadline`),
  KEY `idx_reservation_created_at` (`created_at`),
  KEY `idx_reservation_start_time` (`start_time`),
  KEY `idx_reservation_status_expires_at` (`status`,`expires_at`),
  CONSTRAINT `fk_reservation_equipment` FOREIGN KEY (`equipment_id`) REFERENCES `equipment` (`id`),
  CONSTRAINT `fk_reservation_requester` FOREIGN KEY (`requester_id`) REFERENCES `platform_users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `equipment_reservations`
--

LOCK TABLES `equipment_reservations` WRITE;
/*!40000 ALTER TABLE `equipment_reservations` DISABLE KEYS */;
/*!40000 ALTER TABLE `equipment_reservations` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `fault_work_orders`
--

DROP TABLE IF EXISTS `fault_work_orders`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `fault_work_orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `equipment_id` bigint NOT NULL,
  `reporter_id` bigint NOT NULL,
  `reporter_name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `title` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `priority` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `assignee_id` bigint DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `resolved_at` timestamp(6) NULL DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `equipment_taken_offline` tinyint(1) NOT NULL DEFAULT '0',
  `category` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'FAULT',
  `discrepancy_key` varchar(160) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_work_order_discrepancy_key` (`discrepancy_key`),
  KEY `idx_work_order_equipment_status` (`equipment_id`,`status`),
  KEY `fk_work_order_reporter` (`reporter_id`),
  KEY `idx_work_order_created_at` (`created_at`),
  KEY `idx_work_order_resolved_at` (`resolved_at`),
  KEY `idx_work_order_category_status_priority` (`category`,`status`,`priority`),
  KEY `idx_work_order_status_priority` (`status`,`priority`),
  CONSTRAINT `fk_work_order_equipment` FOREIGN KEY (`equipment_id`) REFERENCES `equipment` (`id`),
  CONSTRAINT `fk_work_order_reporter` FOREIGN KEY (`reporter_id`) REFERENCES `platform_users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `fault_work_orders`
--

LOCK TABLES `fault_work_orders` WRITE;
/*!40000 ALTER TABLE `fault_work_orders` DISABLE KEYS */;
/*!40000 ALTER TABLE `fault_work_orders` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `flyway_schema_history`
--

DROP TABLE IF EXISTS `flyway_schema_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `script` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `flyway_schema_history`
--

LOCK TABLES `flyway_schema_history` WRITE;
/*!40000 ALTER TABLE `flyway_schema_history` DISABLE KEYS */;
INSERT INTO `flyway_schema_history` VALUES (1,'1','init','SQL','V1__init.sql',-1641096379,'labops','2026-11-28 16:09:31',30,1),(2,'2','work order version','SQL','V2__work_order_version.sql',1768629352,'labops','2026-11-28 16:09:31',12,1),(3,'3','platform users','SQL','V3__platform_users.sql',1420908424,'labops','2026-11-28 16:09:31',6,1),(4,'4','reservation expiry','SQL','V4__reservation_expiry.sql',-1537392983,'labops','2026-11-28 16:09:31',9,1),(5,'5','audit and user links','SQL','V5__audit_and_user_links.sql',1073063210,'labops','2026-11-28 16:09:31',36,1),(6,'6','equipment profile','SQL','V6__equipment_profile.sql',2073178484,'labops','2026-11-28 16:09:31',46,1),(7,'7','refresh tokens','SQL','V7__refresh_tokens.sql',79172110,'labops','2026-11-28 16:09:31',12,1),(8,'8','reservation version','SQL','V8__reservation_version.sql',125863547,'labops','2026-11-28 16:09:31',8,1),(9,'9','work order equipment offline','SQL','V9__work_order_equipment_offline.sql',-2035067153,'labops','2026-11-28 16:09:31',14,1),(10,'10','equipment hourly price','SQL','V10__equipment_hourly_price.sql',-548717157,'labops','2026-11-28 16:09:31',12,1),(11,'11','reservation payment deadline','SQL','V11__reservation_payment_deadline.sql',783537117,'labops','2026-11-28 16:09:31',11,1),(12,'12','payment ledger','SQL','V12__payment_ledger.sql',-1197114081,'labops','2026-11-28 16:09:31',20,1),(13,'13','work order payment discrepancy','SQL','V13__work_order_payment_discrepancy.sql',1329399308,'labops','2026-11-28 16:09:31',24,1),(14,'14','payment transaction idempotency','SQL','V14__payment_transaction_idempotency.sql',-813786440,'labops','2026-11-28 16:09:31',4,1),(15,'15','payment outbound requests','SQL','V15__payment_outbound_requests.sql',1203474614,'labops','2026-11-28 16:09:31',16,1),(16,'16','payment request channel attempt','SQL','V16__payment_request_channel_attempt.sql',-1055167616,'labops','2026-11-28 16:09:31',6,1),(17,'17','refresh token families','SQL','V17__refresh_token_families.sql',1457932380,'labops','2026-11-28 16:09:31',51,1),(18,'18','payment request order index','SQL','V18__payment_request_order_index.sql',-1639280450,'labops','2026-11-28 16:09:31',6,1),(19,'19','dashboard trend indexes','SQL','V19__dashboard_trend_indexes.sql',1736868889,'labops','2026-11-28 16:09:31',17,1),(20,'20','work order resolved trend index','SQL','V20__work_order_resolved_trend_index.sql',775728230,'labops','2026-11-28 16:09:31',4,1),(21,'21','dashboard work order and reservation expiry indexes','SQL','V21__dashboard_work_order_and_reservation_expiry_indexes.sql',1935808406,'labops','2026-11-28 16:09:31',19,1);
/*!40000 ALTER TABLE `flyway_schema_history` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `operation_logs`
--

DROP TABLE IF EXISTS `operation_logs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `operation_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actor_user_id` bigint DEFAULT NULL,
  `actor_username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_role` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `action` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_type` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_id` bigint DEFAULT NULL,
  `details` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_operation_log_actor` (`actor_user_id`),
  KEY `idx_operation_log_created_at` (`created_at`),
  KEY `idx_operation_log_target` (`target_type`,`target_id`),
  CONSTRAINT `fk_operation_log_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `platform_users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `operation_logs`
--

LOCK TABLES `operation_logs` WRITE;
/*!40000 ALTER TABLE `operation_logs` DISABLE KEYS */;
/*!40000 ALTER TABLE `operation_logs` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `payment_orders`
--

DROP TABLE IF EXISTS `payment_orders`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment_orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `reservation_id` bigint NOT NULL,
  `equipment_id` bigint NOT NULL,
  `payer_id` bigint NOT NULL,
  `amount_cents` bigint NOT NULL,
  `paid_cents` bigint NOT NULL DEFAULT '0',
  `refunded_cents` bigint NOT NULL DEFAULT '0',
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `order_no` (`order_no`),
  UNIQUE KEY `reservation_id` (`reservation_id`),
  CONSTRAINT `fk_payment_order_reservation` FOREIGN KEY (`reservation_id`) REFERENCES `equipment_reservations` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `payment_orders`
--

LOCK TABLES `payment_orders` WRITE;
/*!40000 ALTER TABLE `payment_orders` DISABLE KEYS */;
/*!40000 ALTER TABLE `payment_orders` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `payment_requests`
--

DROP TABLE IF EXISTS `payment_requests`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment_requests` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `idempotency_key` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `amount_cents` bigint NOT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `attempts` int NOT NULL DEFAULT '0',
  `last_error` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `next_attempt_at` timestamp(6) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  `updated_at` timestamp(6) NOT NULL,
  `channel_attempt` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_request_idempotency` (`idempotency_key`),
  KEY `idx_payment_request_dispatch` (`status`,`next_attempt_at`),
  KEY `idx_payment_request_order` (`order_no`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `payment_requests`
--

LOCK TABLES `payment_requests` WRITE;
/*!40000 ALTER TABLE `payment_requests` DISABLE KEYS */;
/*!40000 ALTER TABLE `payment_requests` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `payment_transactions`
--

DROP TABLE IF EXISTS `payment_transactions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment_transactions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_no` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `idempotency_key` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `amount_cents` bigint NOT NULL,
  `channel_txn_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `channel_status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `occurred_at` timestamp(6) NOT NULL,
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_transaction_idempotency` (`idempotency_key`),
  KEY `idx_payment_transaction_order` (`order_no`),
  KEY `idx_payment_transaction_occurred` (`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `payment_transactions`
--

LOCK TABLES `payment_transactions` WRITE;
/*!40000 ALTER TABLE `payment_transactions` DISABLE KEYS */;
/*!40000 ALTER TABLE `payment_transactions` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `platform_users`
--

DROP TABLE IF EXISTS `platform_users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `platform_users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password_hash` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `display_name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=70 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `platform_users`
--

LOCK TABLES `platform_users` WRITE;
/*!40000 ALTER TABLE `platform_users` DISABLE KEYS */;
INSERT INTO `platform_users` VALUES (1,'student','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','林知夏','STUDENT',1,'2026-11-28 16:09:35.327195'),(2,'teacher','$2a$10$9BmJn5PHRkMLcYBC5pBeIusYKIjV0ccLVizElukVWTjGHVLpGnjAS','周教授','TEACHER',1,'2026-11-28 16:09:35.415371'),(3,'technician','$2a$10$OGeZJ3yRy3qmwG.58BFHxu7ZlJkJU8JEOzyW9T0d./.ad5iCm9b9q','陈工','TECHNICIAN',1,'2026-11-28 16:09:35.477593'),(4,'technician2','$2a$10$slDmm7WzgIYEs4Tk6F5sZe3MAUNo2yJiBuJJrAKPaijxW8rSpOJtS','赵工','TECHNICIAN',1,'2026-11-28 16:09:35.540187'),(5,'admin','$2a$10$U6HyTYKLmx8orfjU.3.gsOK7eXOe0jLswRmGzz.tYe0YykfHK3qhW','系统管理员','ADMIN',1,'2026-11-28 16:09:35.601807'),(6,'system','$2a$10$/coPc/lvRluLMacfk/GBN.q1NWpeFQKF3HKjuBkq26EiJHxCn8ulS','系统','ADMIN',0,'2026-11-28 16:09:35.673328'),(7,'bench01','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ01','STUDENT',1,'2026-11-28 16:09:47.797076'),(8,'bench02','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ02','STUDENT',1,'2026-11-28 16:09:47.797076'),(9,'bench03','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ03','STUDENT',1,'2026-11-28 16:09:47.797076'),(10,'bench04','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ04','STUDENT',1,'2026-11-28 16:09:47.797076'),(11,'bench05','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ05','STUDENT',1,'2026-11-28 16:09:47.797076'),(12,'bench06','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ06','STUDENT',1,'2026-11-28 16:09:47.797076'),(13,'bench07','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ07','STUDENT',1,'2026-11-28 16:09:47.797076'),(14,'bench08','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ08','STUDENT',1,'2026-11-28 16:09:47.797076'),(15,'bench09','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ09','STUDENT',1,'2026-11-28 16:09:47.797076'),(16,'bench10','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ10','STUDENT',1,'2026-11-28 16:09:47.797076'),(17,'bench11','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ11','STUDENT',1,'2026-11-28 16:09:47.797076'),(18,'bench12','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ12','STUDENT',1,'2026-11-28 16:09:47.797076'),(19,'bench13','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ13','STUDENT',1,'2026-11-28 16:09:47.797076'),(20,'bench14','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ14','STUDENT',1,'2026-11-28 16:09:47.797076'),(21,'bench15','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ15','STUDENT',1,'2026-11-28 16:09:47.797076'),(22,'bench16','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ16','STUDENT',1,'2026-11-28 16:09:47.797076'),(23,'bench17','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ17','STUDENT',1,'2026-11-28 16:09:47.797076'),(24,'bench18','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ18','STUDENT',1,'2026-11-28 16:09:47.797076'),(25,'bench19','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ19','STUDENT',1,'2026-11-28 16:09:47.797076'),(26,'bench20','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ20','STUDENT',1,'2026-11-28 16:09:47.797076'),(27,'bench21','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ21','STUDENT',1,'2026-11-28 16:09:47.797076'),(28,'bench22','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ22','STUDENT',1,'2026-11-28 16:09:47.797076'),(29,'bench23','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ23','STUDENT',1,'2026-11-28 16:09:47.797076'),(30,'bench24','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ24','STUDENT',1,'2026-11-28 16:09:47.797076'),(31,'bench25','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ25','STUDENT',1,'2026-11-28 16:09:47.797076'),(32,'bench26','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ26','STUDENT',1,'2026-11-28 16:09:47.797076'),(33,'bench27','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ27','STUDENT',1,'2026-11-28 16:09:47.797076'),(34,'bench28','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ28','STUDENT',1,'2026-11-28 16:09:47.797076'),(35,'bench29','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ29','STUDENT',1,'2026-11-28 16:09:47.797076'),(36,'bench30','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ30','STUDENT',1,'2026-11-28 16:09:47.797076'),(37,'bench31','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ31','STUDENT',1,'2026-11-28 16:09:47.797076'),(38,'bench32','$2a$10$9FgvG0OodM2kUb/UZ2Uxc.QgD166qLH9C.1lphzO3GdSOh9mcKjee','åŽ‹æµ‹å­¦ç”Ÿ32','STUDENT',1,'2026-11-28 16:09:47.797076');
/*!40000 ALTER TABLE `platform_users` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `refresh_token_families`
--

DROP TABLE IF EXISTS `refresh_token_families`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refresh_token_families` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `current_token_id` bigint DEFAULT NULL,
  `revoked_at` timestamp(6) NULL DEFAULT NULL,
  `revoke_reason` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `version` bigint NOT NULL DEFAULT '0',
  `created_at` timestamp(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_refresh_token_family_user` (`user_id`),
  KEY `fk_refresh_token_family_current` (`current_token_id`),
  CONSTRAINT `fk_refresh_token_family_current` FOREIGN KEY (`current_token_id`) REFERENCES `refresh_tokens` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_refresh_token_family_user` FOREIGN KEY (`user_id`) REFERENCES `platform_users` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=34 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `refresh_token_families`
--

LOCK TABLES `refresh_token_families` WRITE;
/*!40000 ALTER TABLE `refresh_token_families` DISABLE KEYS */;
INSERT INTO `refresh_token_families` VALUES (1,5,1,NULL,NULL,1,'2026-11-28 16:09:48.435735'),(2,7,2,NULL,NULL,1,'2026-11-28 16:09:48.604809'),(3,8,3,NULL,NULL,1,'2026-11-28 16:09:48.682862'),(4,9,4,NULL,NULL,1,'2026-11-28 16:09:48.764208'),(5,10,5,NULL,NULL,1,'2026-11-28 16:09:48.840383'),(6,11,6,NULL,NULL,1,'2026-11-28 16:09:48.918387'),(7,12,7,NULL,NULL,1,'2026-11-28 16:09:48.993512'),(8,13,8,NULL,NULL,1,'2026-11-28 16:09:49.070799'),(9,14,9,NULL,NULL,1,'2026-11-28 16:09:49.146942'),(10,15,10,NULL,NULL,1,'2026-11-28 16:09:49.221593'),(11,16,11,NULL,NULL,1,'2026-11-28 16:09:49.293765'),(12,17,12,NULL,NULL,1,'2026-11-28 16:09:49.366833'),(13,18,13,NULL,NULL,1,'2026-11-28 16:09:49.443079'),(14,19,14,NULL,NULL,1,'2026-11-28 16:09:49.515717'),(15,20,15,NULL,NULL,1,'2026-11-28 16:09:49.589445'),(16,21,16,NULL,NULL,1,'2026-11-28 16:09:49.669593'),(17,22,17,NULL,NULL,1,'2026-11-28 16:09:49.749424'),(18,23,18,NULL,NULL,1,'2026-11-28 16:09:49.830767'),(19,24,19,NULL,NULL,1,'2026-11-28 16:09:49.908146'),(20,25,20,NULL,NULL,1,'2026-11-28 16:09:49.983016'),(21,26,21,NULL,NULL,1,'2026-11-28 16:09:50.056940'),(22,27,22,NULL,NULL,1,'2026-11-28 16:09:50.135219'),(23,28,23,NULL,NULL,1,'2026-11-28 16:09:50.211072'),(24,29,24,NULL,NULL,1,'2026-11-28 16:09:50.296585'),(25,30,25,NULL,NULL,1,'2026-11-28 16:09:50.376612'),(26,31,26,NULL,NULL,1,'2026-11-28 16:09:50.455729'),(27,32,27,NULL,NULL,1,'2026-11-28 16:09:50.532152'),(28,33,28,NULL,NULL,1,'2026-11-28 16:09:50.608351'),(29,34,29,NULL,NULL,1,'2026-11-28 16:09:50.681073'),(30,35,30,NULL,NULL,1,'2026-11-28 16:09:50.755554'),(31,36,31,NULL,NULL,1,'2026-11-28 16:09:50.828285'),(32,37,32,NULL,NULL,1,'2026-11-28 16:09:50.899941'),(33,38,33,NULL,NULL,1,'2026-11-28 16:09:50.971987');
/*!40000 ALTER TABLE `refresh_token_families` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `refresh_tokens`
--

DROP TABLE IF EXISTS `refresh_tokens`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refresh_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `token_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `expires_at` timestamp(6) NOT NULL,
  `revoked_at` timestamp(6) NULL DEFAULT NULL,
  `created_at` timestamp(6) NOT NULL,
  `family_id` bigint NOT NULL,
  `revoke_reason` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `token_hash` (`token_hash`),
  KEY `idx_refresh_token_user` (`user_id`),
  KEY `idx_refresh_token_expires` (`expires_at`),
  KEY `idx_refresh_token_family_id` (`family_id`),
  CONSTRAINT `fk_refresh_token_family` FOREIGN KEY (`family_id`) REFERENCES `refresh_token_families` (`id`),
  CONSTRAINT `fk_refresh_token_user` FOREIGN KEY (`user_id`) REFERENCES `platform_users` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=34 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `refresh_tokens`
--

LOCK TABLES `refresh_tokens` WRITE;
/*!40000 ALTER TABLE `refresh_tokens` DISABLE KEYS */;
INSERT INTO `refresh_tokens` VALUES (1,5,'fbeef6d312196d95ac9a583c1d902590a69ea54f8f561d3b9ac97f0161066b2f','2026-12-05 16:09:48.439576',NULL,'2026-11-28 16:09:48.440064',1,NULL),(2,7,'6d649d80410ee37bb3081ed46bf3f5ade4aa42b8a83080e791fbfe15e5c813ad','2026-12-05 16:09:48.606261',NULL,'2026-11-28 16:09:48.606362',2,NULL),(3,8,'7ffeb4a5734034426b3ca08c5c1ccb7b608a6e950cd14c1410c847c3428047cd','2026-12-05 16:09:48.684213',NULL,'2026-11-28 16:09:48.684324',3,NULL),(4,9,'be46d3e2345ce72ee960357dae7318eb49d823fc9442f083bd93fe2e55c87d89','2026-12-05 16:09:48.765320',NULL,'2026-11-28 16:09:48.765439',4,NULL),(5,10,'f7a46efecd395fe572fa2f4bf5754a27bda1522c19052f3f5e9672a37a9ecee1','2026-12-05 16:09:48.841472',NULL,'2026-11-28 16:09:48.841546',5,NULL),(6,11,'28b28f5247ed66a16f44c20511253635ababb7c16099bca6968ab77cb6cd8b7b','2026-12-05 16:09:48.919855',NULL,'2026-11-28 16:09:48.919960',6,NULL),(7,12,'26251096a7ada1f031fc0ec6f187d982c6fd8e810cec4894b82ff7436bdef5ee','2026-12-05 16:09:48.994908',NULL,'2026-11-28 16:09:48.994975',7,NULL),(8,13,'5b8bd4dca82886b44724d7a360ccfbf34d1ed0af7925fcab2cd6958c004676f2','2026-12-05 16:09:49.072131',NULL,'2026-11-28 16:09:49.072211',8,NULL),(9,14,'278b814d2719cf6322ac118444a557687d4dbbf65509b593a44465cad000d449','2026-12-05 16:09:49.148376',NULL,'2026-11-28 16:09:49.148468',9,NULL),(10,15,'f651218c225170c613ab412425296b3ebed86dafffa95250311b8a702dfc76f9','2026-12-05 16:09:49.222604',NULL,'2026-11-28 16:09:49.222677',10,NULL),(11,16,'aa39134fd5bdfa88b9880430d3a064a03f798d9b22c072274cc1cb916797c4d9','2026-12-05 16:09:49.294748',NULL,'2026-11-28 16:09:49.294820',11,NULL),(12,17,'19a6cbdb25fb3920b511032013eaf89fdd6b91892d6dd00338a1d6887dc00194','2026-12-05 16:09:49.367838',NULL,'2026-11-28 16:09:49.367931',12,NULL),(13,18,'9c4898608289d38d6ccf53e3b3f66dbe2f3d88da5f392a8d4130e3f36e0c680d','2026-12-05 16:09:49.444056',NULL,'2026-11-28 16:09:49.444128',13,NULL),(14,19,'62053dfd13faf85bd1a7d7d1f3033b6bb58f70f75b73edef43a5251d6a41bd47','2026-12-05 16:09:49.516681',NULL,'2026-11-28 16:09:49.516766',14,NULL),(15,20,'9ccc5f102fe7019d5ba2b569ffe7d3a3d0f8da212697012c559f1ac136c3b963','2026-12-05 16:09:49.590296',NULL,'2026-11-28 16:09:49.590368',15,NULL),(16,21,'df9dd56ba7df08a4905d409968e2a9524da5e36ed0d0b39e9e2957d1b758e19b','2026-12-05 16:09:49.670637',NULL,'2026-11-28 16:09:49.670737',16,NULL),(17,22,'9b40c6e1438575a5d9ade78f705ac975777b8e8befa423d8def72e3d5e61c195','2026-12-05 16:09:49.750543',NULL,'2026-11-28 16:09:49.750638',17,NULL),(18,23,'6a3524f7dda913783c870018447f055c1535b7b9535c399c2f3b2305b9be9664','2026-12-05 16:09:49.831797',NULL,'2026-11-28 16:09:49.831923',18,NULL),(19,24,'64da809ef98c6dc8e0405ad34c03e9a2f7022e8733e429c3d89a71f7f13163f3','2026-12-05 16:09:49.909189',NULL,'2026-11-28 16:09:49.909266',19,NULL),(20,25,'e9c10acf82514b36336d7e31023a91d55a30c8df0b3df9b01b33e8ee9714d4ff','2026-12-05 16:09:49.984005',NULL,'2026-11-28 16:09:49.984081',20,NULL),(21,26,'aca4e110b226d0412bdd8eea02f9abff044dd3e6097849d1f5622dafb6dd720e','2026-12-05 16:09:50.057857',NULL,'2026-11-28 16:09:50.057940',21,NULL),(22,27,'2bc965a5553ee7ec49f9b16d9e204ebf09c52e684945b2cf8825d7d514b45da3','2026-12-05 16:09:50.136440',NULL,'2026-11-28 16:09:50.136551',22,NULL),(23,28,'1b9d336d1734a1ce5c777942d453e666add8af7d1c249533d976587ae8893f16','2026-12-05 16:09:50.212008',NULL,'2026-11-28 16:09:50.212078',23,NULL),(24,29,'5e2cef8b509033219ddc021a006c9be51ff68345bf1c8ed8b517c4b9563f32e3','2026-12-05 16:09:50.298048',NULL,'2026-11-28 16:09:50.298187',24,NULL),(25,30,'2b7a2e297785df778921751735e3187b9d3bccd5e0fd3c3ab6b59ee178911aa3','2026-12-05 16:09:50.377992',NULL,'2026-11-28 16:09:50.378110',25,NULL),(26,31,'8b25afb616da049dcff486e18dcd2021ca058e0abf6fba915d2155148cfe5675','2026-12-05 16:09:50.457020',NULL,'2026-11-28 16:09:50.457105',26,NULL),(27,32,'6fc62bc2761d590f79d641cebe14f56ec7aa1337500ffcd1ba0a09857ca9205a','2026-12-05 16:09:50.533018',NULL,'2026-11-28 16:09:50.533090',27,NULL),(28,33,'3578ea1ab15366b5ff7fb026e564c208658d0f0e78e34cf59c2a0e73043deac3','2026-12-05 16:09:50.609307',NULL,'2026-11-28 16:09:50.609427',28,NULL),(29,34,'700f19eff579c1899a9eb4f866ee6f68527516b0c50fa15c3831b25f597e78c6','2026-12-05 16:09:50.682592',NULL,'2026-11-28 16:09:50.682678',29,NULL),(30,35,'c10fcce0e08e4949362100d8c31aeb1dec0e1c7e8db1f8e57f948262896ad107','2026-12-05 16:09:50.756548',NULL,'2026-11-28 16:09:50.756618',30,NULL),(31,36,'68b43358a9e27d222e16e03202bf1ef7e5ea871322e5f8c0349c83eaa970602e','2026-12-05 16:09:50.829247',NULL,'2026-11-28 16:09:50.829310',31,NULL),(32,37,'ca06db4dc5290721d3f67bd1bac2f92143b289f2754ea9db1f707798652323cc','2026-12-05 16:09:50.900720',NULL,'2026-11-28 16:09:50.900797',32,NULL),(33,38,'0ff0eadf72cd4b3e0242d31c094017ff29178e1d8af4fe4db797385c143c2971','2026-12-05 16:09:50.972928',NULL,'2026-11-28 16:09:50.972988',33,NULL);
/*!40000 ALTER TABLE `refresh_tokens` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping routines for database 'labops'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-11-28 16:09:58
