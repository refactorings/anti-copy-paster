from common import common
from extractor import Extractor

SHOW_TOP_CONTEXTS = 10
MAX_PATH_LENGTH = 8
MAX_PATH_WIDTH = 2
JAR_PATH = 'JavaExtractor/JPredict/target/JavaExtractor-0.0.1-SNAPSHOT.jar'

class InteractivePredictor:
    def __init__(self, config, model):
        self.model = model
        self.config = config
        self.path_extractor = Extractor(config,
                                        jar_path=JAR_PATH,
                                        max_path_length=MAX_PATH_LENGTH,
                                        max_path_width=MAX_PATH_WIDTH)

    def predict(self, method):
        #input_filename = 'Input.java'
        print('Starting interactive prediction...')
        try:
            predict_lines, hash_to_string_dict = self.path_extractor.extract_paths(method)
        except ValueError as e:
            print(e)
            return
        
        #self.model.set_predict_reader()
        raw_prediction_results = self.model.predict(predict_lines)
        method_prediction_results = common.parse_prediction_results(
            raw_prediction_results, hash_to_string_dict,
            self.model.vocabs.target_vocab.special_words, topk=SHOW_TOP_CONTEXTS)
        for raw_prediction, method_prediction in zip(raw_prediction_results, method_prediction_results):
            output = 'Original name:\t' + method_prediction.original_name
            #print('Original name:\t' + method_prediction.original_name)
            for name_prob_pair in method_prediction.predictions:
                output = output + ('\t(%f) predicted: %s' % (name_prob_pair['probability'], name_prob_pair['name']))
                #print('\t(%f) predicted: %s' % (name_prob_pair['probability'], name_prob_pair['name']))
        return output
