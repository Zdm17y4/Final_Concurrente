"""
Training Client - Send training data to distributed workers

Usage:
    python -m src.train_client --host 127.0.0.1 --port 9000 train inputs.csv outputs.csv
    python -m src.train_client --host 127.0.0.1 --port 9000 train-inline "0,0;0,1;1,0;1,1" "0;1;1;0"
"""
import socket
import json
import argparse
import time


def send_train_request(host: str, port: int, inputs: list, outputs: list):
    """
    Send training data to a worker and receive model ID.
    
    Args:
        host: Worker host
        port: Worker port
        inputs: List of input vectors (each is a list of floats)
        outputs: List of output vectors (each is a list of floats)
    
    Returns:
        model_id if successful, None otherwise
    """
    message = json.dumps({
        'type': 'TRAIN',
        'inputs': inputs,
        'outputs': outputs
    })
    
    attempt = 0
    cur_host, cur_port = host, port
    
    while attempt < 5:
        try:
            with socket.create_connection((cur_host, cur_port), timeout=120) as s:
                # Send message with newline terminator
                s.sendall((message + '\n').encode('utf-8'))
                
                # Receive response (may take time for training)
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
                    model_id = resp.get('model_id')
                    print(f'Training complete!')
                    print(f'Model ID: {model_id}')
                    return model_id
                
                if resp.get('status') == 'REDIRECT':
                    leader = resp.get('leader')
                    if leader:
                        cur_host, cur_port = leader[0], int(leader[1])
                        print(f'Redirecting to leader {cur_host}:{cur_port}')
                        attempt += 1
                        continue
                
                print('Server response:', resp)
                return None
                
        except Exception as e:
            print(f'Connection error: {e}')
            attempt += 1
            time.sleep(0.5)
    
    print('Failed after retries')
    return None


def load_csv(filepath: str) -> list:
    """Load CSV file into list of float lists."""
    data = []
    with open(filepath, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = [float(x.strip()) for x in line.split(',')]
            data.append(row)
    return data


def parse_inline(s: str) -> list:
    """Parse inline format: '0,0;0,1;1,0' -> [[0,0], [0,1], [1,0]]"""
    rows = []
    for part in s.split(';'):
        row = [float(x.strip()) for x in part.split(',')]
        rows.append(row)
    return rows


def main():
    parser = argparse.ArgumentParser(description='Training Client')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=9000)
    
    subparsers = parser.add_subparsers(dest='command')
    
    # train command with CSV files
    train_parser = subparsers.add_parser('train', help='Train with CSV files')
    train_parser.add_argument('inputs_file', help='CSV file with inputs')
    train_parser.add_argument('outputs_file', help='CSV file with outputs')
    
    # train-inline command for quick testing
    inline_parser = subparsers.add_parser('train-inline', help='Train with inline data')
    inline_parser.add_argument('inputs', help='Inputs as "0,0;0,1;1,0;1,1"')
    inline_parser.add_argument('outputs', help='Outputs as "0;1;1;0"')
    
    args = parser.parse_args()
    
    if args.command == 'train':
        inputs = load_csv(args.inputs_file)
        outputs = load_csv(args.outputs_file)
        print(f'Loaded {len(inputs)} samples from files')
        send_train_request(args.host, args.port, inputs, outputs)
        
    elif args.command == 'train-inline':
        inputs = parse_inline(args.inputs)
        outputs = parse_inline(args.outputs)
        print(f'Training with {len(inputs)} samples (inline)')
        send_train_request(args.host, args.port, inputs, outputs)
        
    else:
        parser.print_help()


if __name__ == '__main__':
    main()
