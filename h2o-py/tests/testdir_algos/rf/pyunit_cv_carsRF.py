import sys
sys.path.insert(1, "../../../")
import h2o
import random

def cv_carsRF(ip,port):

    # read in the dataset and construct training set (and validation set)
    cars =  h2o.import_file(path=h2o.locate("smalldata/junit/cars_20mpg.csv"))

    # choose the type model-building exercise (multinomial classification or regression). 0:regression, 1:binomial,
    # 2:multinomial
    problem = random.sample(range(3),1)[0]

    # pick the predictors and the correct response column
    predictors = ["displacement","power","weight","acceleration","year"]
    if problem == 1   :
        response_col = "economy_20mpg"
        cars[response_col] = cars[response_col].asfactor()
    elif problem == 2 :
        response_col = "cylinders"
        cars[response_col] = cars[response_col].asfactor()
    else              :
        response_col = "economy"

    print "Response column: {0}".format(response_col)

    ## cross-validation
    # 1. check that cv metrics are the same over repeated seeded "Modulo" runs
    nfolds = random.randint(3,10)
    rf1 = h2o.random_forest(y=cars[response_col], x=cars[predictors], nfolds=nfolds, fold_assignment="Modulo", seed=1234)
    rf2 = h2o.random_forest(y=cars[response_col], x=cars[predictors], nfolds=nfolds, fold_assignment="Modulo", seed=1234)
    h2o.check_models(rf1, rf2, True)

    # 2. check that cv metrics are different over repeated "Random" runs
    nfolds = random.randint(3,10)
    rf1 = h2o.random_forest(y=cars[response_col], x=cars[predictors], nfolds=nfolds, fold_assignment="Random")
    rf2 = h2o.random_forest(y=cars[response_col], x=cars[predictors], nfolds=nfolds, fold_assignment="Random")
    try:
        h2o.check_models(rf1, rf2, True)
        assert False, "Expected models to be different over repeated Random runs"
    except AssertionError:
        assert True

    # 3. folds_column
    num_folds = random.randint(2,5)
    fold_assignments = h2o.H2OFrame(python_obj=[[random.randint(0,num_folds-1)] for f in range(cars.nrow())])
    fold_assignments.setNames(["fold_assignments"])
    cars = cars.cbind(fold_assignments)
    rf = h2o.random_forest(y=cars[response_col], x=cars[predictors], training_frame=cars,
                           fold_column="fold_assignments", keep_cross_validation_predictions=True)
    num_cv_models = len(rf._model_json['output']['cross_validation_models'])
    assert num_cv_models==num_folds, "Expected {0} cross-validation models, but got " \
                                                    "{1}".format(num_folds, num_cv_models)
    cv_model1 = h2o.get_model(rf._model_json['output']['cross_validation_models'][0]['name'])
    cv_model2 = h2o.get_model(rf._model_json['output']['cross_validation_models'][1]['name'])
    assert isinstance(cv_model1, type(rf)), "Expected cross-validation model to be the same model type as the " \
                                             "constructed model, but got {0} and {1}".format(type(cv_model1),type(rf))
    assert isinstance(cv_model2, type(rf)), "Expected cross-validation model to be the same model type as the " \
                                             "constructed model, but got {0} and {1}".format(type(cv_model2),type(rf))

    # 4. keep_cross_validation_predictions
    cv_predictions = rf1._model_json['output']['cross_validation_predictions']
    assert cv_predictions is None, "Expected cross-validation predictions to be None, but got {0}".format(cv_predictions)

    cv_predictions = rf._model_json['output']['cross_validation_predictions']
    assert len(cv_predictions)==num_folds, "Expected the same number of cross-validation predictions " \
                                                          "as folds, but got {0}".format(len(cv_predictions))


    ## boundary cases
    # 1. nfolds = number of observations (leave-one-out cross-validation)
    rf = h2o.random_forest(y=cars[response_col], x=cars[predictors], nfolds=cars.nrow(), fold_assignment="Modulo")

    # 2. nfolds = 0
    rf1 = h2o.random_forest(y=cars[response_col], x=cars[predictors], nfolds=0, seed=1234)
    # check that this is equivalent to no nfolds
    rf2 = h2o.random_forest(y=cars[response_col], x=cars[predictors], seed=1234)
    h2o.check_models(rf1, rf2)

    # 3. cross-validation and regular validation attempted
    rf = h2o.random_forest(y=cars[response_col], x=cars[predictors], nfolds=random.randint(3,10),
                           validation_y=cars[response_col], validation_x=cars[predictors])


    ## error cases
    # 1. nfolds == 1 or < 0
    try:
        rf = h2o.random_forest(y=cars[response_col], x=cars[predictors], nfolds=random.sample([-1,1], 1)[0])
        assert False, "Expected model-build to fail when nfolds is 1 or < 0"
    except EnvironmentError:
        assert True

    # 2. more folds than observations
    try:
        rf = h2o.random_forest(y=cars[response_col], x=cars[predictors], nfolds=cars.nrow()+1, fold_assignment="Modulo")
        assert False, "Expected model-build to fail when nfolds > nobs"
    except EnvironmentError:
        assert True

    # 3. fold_column and nfolds both specified
    try:
        rf = h2o.random_forest(y=cars[response_col], x=cars[predictors], nfolds=3, fold_column="fold_assignments",
                               training_frame=cars)
        assert False, "Expected model-build to fail when fold_column and nfolds both specified"
    except EnvironmentError:
        assert True

    # # 4. fold_column and fold_assignment both specified
    # try:
    #     rf = h2o.random_forest(y=cars[response_col], x=cars[predictors], fold_assignment="Random",
    #                            fold_column="fold_assignments", training_frame=cars)
    #     assert False, "Expected model-build to fail when fold_column and fold_assignment both specified"
    # except EnvironmentError:
    #     assert True

if __name__ == "__main__":
    h2o.run_test(sys.argv, cv_carsRF)
