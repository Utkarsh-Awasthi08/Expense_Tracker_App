from flask import Flask
from flask import request, jsonify
from .services.messageService import MessageService
from kafka import KafkaProducer
import json
import os
import jsonpickle

app = Flask(__name__)
app.config.from_pyfile('config.py')

messageService = MessageService()
kafka_host = os.getenv('KAFKA_HOST', 'localhost')
kafka_port = os.getenv('KAFKA_PORT', '9092')
kafka_bootstrap_servers = f"{kafka_host}:{kafka_port}"
print("Kafka server is "+kafka_bootstrap_servers)
print("\n")
producer = KafkaProducer(bootstrap_servers=kafka_bootstrap_servers,
                         value_serializer=lambda v: json.dumps(v).encode('utf-8'))

import hashlib
import uuid
from datetime import datetime, timezone

@app.route('/v1/ds/ingest', methods=['POST'])
def handle_ingest():
    user_id = request.headers.get('X-User-Id')
    if not user_id:
        return jsonify({'error': 'Missing X-User-Id header'}), 401

    payload = request.json
    messages = payload.get('messages', [])
    if not messages:
        return jsonify({'status': 'ok', 'processed': 0})

    processed_count = 0
    for msg in messages:
        # Expected msg: { "id": "...", "address": "...", "body": "...", "date": "..." }
        body = msg.get('body', '')
        if not body:
            continue

        result = messageService.process_message(body)
        
        if result is not None:
            # Result is an instance of Expense
            serialized_result = result.serialize()
            
            # Augment with required fields for expenseService
            sms_hash = hashlib.sha256(body.encode('utf-8')).hexdigest()
            external_id = str(uuid.uuid4())
            
            # Try to parse date, fallback to now
            try:
                date_ms = int(msg.get('date', 0))
                sms_received_at = datetime.fromtimestamp(date_ms/1000.0, tz=timezone.utc).isoformat()
                txn_date = datetime.fromtimestamp(date_ms/1000.0, tz=timezone.utc).strftime('%Y-%m-%d')
            except:
                sms_received_at = datetime.now(timezone.utc).isoformat()
                txn_date = datetime.now(timezone.utc).strftime('%Y-%m-%d')

            augmented = {
                **serialized_result,
                "user_id": user_id,
                "external_id": external_id,
                "sms_hash": sms_hash,
                "sms_received_at": sms_received_at,
                "txn_date": txn_date,
                "txn_type": "DEBIT",
                "category": "Uncategorized"
            }
            
            producer.send('expense_service', augmented)
            producer.flush()
            processed_count += 1

    return jsonify({'status': 'ok', 'processed': processed_count})

@app.route('/v1/ds/manual', methods=['POST'])
def handle_manual_ingest():
    user_id = request.headers.get('X-User-Id')
    if not user_id:
        return jsonify({'error': 'Missing X-User-Id header'}), 401

    payload = request.json
    text = payload.get('text', '')
    if not text:
        return jsonify({'error': 'Missing text'}), 400

    result = messageService.llmService.runLLM(text)
    
    if result is not None:
        serialized_result = result.serialize()
        
        # Synthetic hash for manual entry
        sms_hash = hashlib.sha256(f"manual_{user_id}_{datetime.now(timezone.utc).isoformat()}_{text}".encode('utf-8')).hexdigest()
        external_id = str(uuid.uuid4())
        
        now = datetime.now(timezone.utc)
        sms_received_at = now.isoformat()
        txn_date = now.strftime('%Y-%m-%d')

        augmented = {
            **serialized_result,
            "user_id": user_id,
            "external_id": external_id,
            "sms_hash": sms_hash,
            "sms_received_at": sms_received_at,
            "txn_date": txn_date,
            "txn_type": "DEBIT",
            "category": "Uncategorized"
        }
        
        producer.send('expense_service', augmented)
        producer.flush()
        return jsonify({'status': 'ok', 'expense': augmented})
    else:
        return jsonify({'error': 'Failed to parse expense'}), 400

@app.route('/', methods=['GET'])
def handle_get():
    return 'Hello world'


if __name__ == "__main__":
    app.run(host="localhost", port= 8010 ,debug=True)