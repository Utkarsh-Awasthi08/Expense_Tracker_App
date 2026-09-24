import pytest
import sys
import os
from unittest.mock import patch, MagicMock

# Add src to path so we can import app
sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

# Mock KafkaProducer before importing app
with patch('kafka.KafkaProducer', MagicMock()):
    from app import app

@pytest.fixture
def client():
    app.config['TESTING'] = True
    with app.test_client() as client:
        yield client

@patch('app.producer.send')
@patch('app.messageService.process_message')
def test_handle_ingest_no_user_id(mock_process, mock_send, client):
    rv = client.post('/v1/ds/ingest', json={'messages': []})
    assert rv.status_code == 401
    assert b'Missing X-User-Id header' in rv.data

@patch('app.producer.send')
@patch('app.messageService.process_message')
def test_handle_ingest_empty_messages(mock_process, mock_send, client):
    rv = client.post('/v1/ds/ingest', headers={'X-User-Id': '123'}, json={'messages': []})
    assert rv.status_code == 200
    assert rv.json['processed'] == 0

@patch('app.producer.send')
@patch('app.messageService.process_message')
def test_handle_ingest_with_messages(mock_process, mock_send, client):
    mock_result = MagicMock()
    mock_result.serialize.return_value = {
        'amount': '100',
        'merchant': 'Test Merchant',
        'currency': 'USD'
    }
    mock_process.return_value = mock_result

    payload = {
        'messages': [
            {'body': 'Spent $100 at Test Merchant', 'date': '1700000000000'},
            {'body': 'Another text'}
        ]
    }
    rv = client.post('/v1/ds/ingest', headers={'X-User-Id': '123'}, json=payload)
    
    assert rv.status_code == 200
    assert rv.json['processed'] == 2
    assert mock_send.call_count == 2
