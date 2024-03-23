from vocabularies import VocabType
from config import Config
from interactive_predict import InteractivePredictor
from model_base import Code2VecModelBase
import socket
def load_model_dynamically(config: Config) -> Code2VecModelBase:
    assert config.DL_FRAMEWORK in {'tensorflow', 'keras'}
    if config.DL_FRAMEWORK == 'tensorflow':
        from tensorflow_model import Code2VecModel
    elif config.DL_FRAMEWORK == 'keras':
        from keras_model import Code2VecModel
    return Code2VecModel(config)


if __name__ == '__main__':
    HOST = "localhost"
    PORT = 8081
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, PORT))
    config = Config(set_defaults=True, load_from_args=True, verify=True)
    model = load_model_dynamically(config)
    while True:
        data = sock.recv(131072)
        predictor = InteractivePredictor(config, model)
        data = predictor.predict(data.decode())
        sock.send((data+'\n').encode('utf-8'))