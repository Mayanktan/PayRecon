package com.payrecon.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.WriteModel;
import com.payrecon.bank.Bank;
import com.payrecon.bank.BankParsers;
import com.payrecon.bank.ParsedLine;
import com.payrecon.config.Config;
import org.bson.Document;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB holds two kinds of documents:
 *  bank_raw_records   every bank line exactly as received, with the bank's OWN field names. Each bank
 *                     uses a different format (CSV, pipe-separated, nested JSON), and a document store
 *                     keeps each shape as-is. Analysts open these when investigating an exception.
 *  payout_statements  one ready-made document per merchant payout (the payments, fees and refunds inside it),
 *                     so a merchant's statement is ONE fast read instead of joining ledger tables.
 *                     This is a "read model": a copy shaped for reading, rebuilt from the ledger (CQRS).
 */
public final class MongoStore implements AutoCloseable {

    public static final String RAW = "bank_raw_records";
    public static final String STATEMENTS = "payout_statements";
    private static final int BULK_SIZE = 2_000;

    private final MongoClient client;
    private final MongoDatabase db;

    public MongoStore(Config config) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(config.mongoUri()))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .build();
        this.client = MongoClients.create(settings);
        this.db = client.getDatabase(config.mongoDatabase());
    }

    public void ping() {
        db.runCommand(new Document("ping", 1));
    }

    public void ensureIndexes() {
        MongoCollection<Document> raw = db.getCollection(RAW);
        raw.createIndex(Indexes.ascending("normalized.reference"));
        raw.createIndex(Indexes.ascending("bank", "normalized.value_date"));
        raw.createIndex(Indexes.ascending("parse_status"));
        db.getCollection(STATEMENTS).createIndex(
                Indexes.compoundIndex(Indexes.ascending("merchant_id"), Indexes.descending("payout_date")));
    }

    public void dropAll() {
        db.getCollection(RAW).drop();
        db.getCollection(STATEMENTS).drop();
    }

    /** Upserts every line of the given bank files (re-running replaces documents, never duplicates them). */
    public long ingestBankFiles(List<Path> files) throws IOException {
        MongoCollection<Document> raw = db.getCollection(RAW);
        List<WriteModel<Document>> batch = new ArrayList<>(BULK_SIZE);
        ReplaceOptions upsert = new ReplaceOptions().upsert(true);
        long count = 0;
        Date now = new Date();
        for (Path file : files) {
            Bank bank = Bank.valueOf(file.getParent().getFileName().toString());
            String fileName = file.getFileName().toString();
            try (BufferedReader reader = Files.newBufferedReader(file)) {
                String line;
                long lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    ParsedLine p = BankParsers.parse(bank, fileName, lineNo, line);
                    if (p.status() == ParsedLine.Status.SKIP) {
                        continue;
                    }
                    Document doc = new Document("_id", p.lineId())
                            .append("bank", bank.name())
                            .append("file", fileName)
                            .append("line_no", lineNo)
                            .append("raw", line)
                            .append("parse_status", p.status().name())
                            .append("ingested_at", now);
                    if (p.status() == ParsedLine.Status.OK) {
                        doc.append("fields", new Document(p.fields()))
                                .append("normalized", new Document("direction", p.direction())
                                        .append("reference", p.ref())
                                        .append("ref_quality", p.refQuality())
                                        .append("amount_minor", p.amountMinor())
                                        .append("value_date", p.valueDate().toString())
                                        .append("bank_txn_id", p.bankTxnId()));
                    } else {
                        doc.append("error", p.error());
                    }
                    batch.add(new ReplaceOneModel<>(Filters.eq("_id", p.lineId()), doc, upsert));
                    count++;
                    if (batch.size() >= BULK_SIZE) {
                        raw.bulkWrite(batch, new BulkWriteOptions().ordered(false));
                        batch.clear();
                    }
                }
            }
        }
        if (!batch.isEmpty()) {
            raw.bulkWrite(batch, new BulkWriteOptions().ordered(false));
        }
        return count;
    }

    public Document rawRecord(String lineId) {
        return db.getCollection(RAW).find(Filters.eq("_id", lineId)).first();
    }

    public void upsertStatements(Collection<Document> docs) {
        MongoCollection<Document> statements = db.getCollection(STATEMENTS);
        List<WriteModel<Document>> batch = new ArrayList<>(BULK_SIZE);
        ReplaceOptions upsert = new ReplaceOptions().upsert(true);
        for (Document doc : docs) {
            batch.add(new ReplaceOneModel<>(Filters.eq("_id", doc.get("_id")), doc, upsert));
            if (batch.size() >= BULK_SIZE) {
                statements.bulkWrite(batch, new BulkWriteOptions().ordered(false));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            statements.bulkWrite(batch, new BulkWriteOptions().ordered(false));
        }
    }

    public List<Document> statements(String merchantId, int limit) {
        return db.getCollection(STATEMENTS).find(Filters.eq("merchant_id", merchantId))
                .sort(Sorts.descending("payout_date")).limit(limit).into(new ArrayList<>());
    }

    public long count(String collection) {
        return db.getCollection(collection).countDocuments();
    }

    @Override
    public void close() {
        client.close();
    }
}
