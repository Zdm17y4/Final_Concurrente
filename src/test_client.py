"""
Test Client - Query trained models for predictions

Usage:
    python -m src.test_client --host 127.0.0.1 --port 9000 predict <model_id> 1,0,1,0
    python -m src.test_client --host 127.0.0.1 --port 9000 list
"""
import socket
import json
import argparse
import time


def send_predict_request(host: str, port: int, model_id: str, input_data: list):
    """
    Send prediction request to a worker.
    
    Args:
        host: Worker host
        port: Worker port
        model_id: UUID of the trained model
        input_data: List of floats as input
    
    Returns:
        prediction output if successful, None otherwise
    """
    message = json.dumps({
        'type': 'PREDICT',
        'model_id': model_id,
        'input': input_data
    })
    
    attempt = 0
    cur_host, cur_port = host, port
    
    while attempt < 5:
        try:
            with socket.create_connection((cur_host, cur_port), timeout=30) as s:
                s.sendall((message + '\n').encode('utf-8'))
                
                response_data = b''
                while True:
                    chunk = s.recv(4096)
                    if not chunk:
                        break
                    response_data += chunk
                    if b'\n' in chunk:
                        break
                
                try:
                    resp = json.loads(response_data.decode('utf-8').strip())
                except Exception:
                    print('Response (raw):', response_data)
                    return None
                
                if resp.get('status') == 'OK':
                    output = resp.get('output')
                    print(f'Prediction: {output}')
                    return output
                
                if resp.get('status') == 'REDIRECT':
                    leader = resp.get('leader')
                    if leader:
                        cur_host, cur_port = leader[0], int(leader[1])
                        print(f'Redirecting to leader {cur_host}:{cur_port}')
                        attempt += 1
                        continue
                
                if resp.get('status') == 'ERROR':
                    print(f'Error: {resp.get("message")}')
                    return None
                
                print('Server response:', resp)
                return None
                
        except Exception as e:
            print(f'Connection error: {e}')
            attempt += 1
            time.sleep(0.5)
    
    print('Failed after retries')
    return None


def list_models(host: str, port: int):
    """Request list of available models from worker."""
    message = json.dumps({'type': 'LIST_MODELS'})
    
    try:
        with socket.create_connection((host, port), timeout=10) as s:
            s.sendall((message + '\n').encode('utf-8'))
            
            response_data = b''
            while True:
                chunk = s.recv(4096)
                if not chunk:
                    break
                response_data += chunk
                if b'\n' in chunk:
                    break
            
            resp = json.loads(response_data.decode('utf-8').strip())
            
            if resp.get('status') == 'OK':
                models = resp.get('models', [])
                print(f'Available models ({len(models)}):')
                for m in models:
                    print(f'  - {m}')
                return models
            
            print('Response:', resp)
            return []
            
    except Exception as e:
        print(f'Error: {e}')
        return []


def main():
    parser = argparse.ArgumentParser(description='Test Client - Query trained models')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=9000)
    
    subparsers = parser.add_subparsers(dest='command')
    
    # predict command
    predict_parser = subparsers.add_parser('predict', help='Make a prediction')
    predict_parser.add_argument('model_id', help='UUID of the model')
    predict_parser.add_argument('input', help='Input values as comma-separated: 1,0,1,0')
    
    # list command
    subparsers.add_parser('list', help='List available models')
    
    args = parser.parse_args()
    
    if args.command == 'predict':
        input_data = [float(x.strip()) for x in args.input.split(',')]
        send_predict_request(args.host, args.port, args.model_id, input_data)
        
    elif args.command == 'list':
        list_models(args.host, args.port)
        
    else:
        parser.print_help()


if __name__ == '__main__':
    main()
