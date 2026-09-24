import pytest
import sys
import os
import json
from unittest.mock import patch, MagicMock

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

with patch('kafka.KafkaProducer', MagicMock()):
    from app import app

@pytest.fixture
def client():
    app.config['TESTING'] = True
    with app.test_client() as client:
        yield client

@patch('app.producer.send')
@patch('app.messageService.llmService.runLLM')
def test_handle_manual_ingest_no_user(mock_runLLM, mock_send, client):
    rv = client.post('/v1/ds/manual', json={'text': 'Spent 20 on coffee'})
    assert rv.status_code == 401

@patch('app.producer.send')
@patch('app.messageService.llmService.runLLM')
def test_handle_manual_ingest_no_text(mock_runLLM, mock_send, client):
    rv = client.post('/v1/ds/manual', headers={'X-User-Id': '123'}, json={'text': ''})
    assert rv.status_code == 400

@patch('app.producer.send')
@patch('app.messageService.llmService.runLLM')
def test_handle_manual_ingest_success(mock_runLLM, mock_send, client):
    mock_result = MagicMock()
    mock_result.serialize.return_value = {
        'amount': '20',
        'merchant': 'Starbucks',
        'currency': 'USD'
    }
    mock_runLLM.return_value = mock_result

    payload = {'text': 'Spent $20 at Starbucks'}
    rv = client.post('/v1/ds/manual', headers={'X-User-Id': 'user-123'}, json=payload)
    
    assert rv.status_code == 200
    assert rv.json['status'] == 'ok'
    assert rv.json['expense']['amount'] == '20'
    assert mock_send.call_count == 1
