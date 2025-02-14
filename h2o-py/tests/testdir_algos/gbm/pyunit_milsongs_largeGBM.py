import sys, shutil
sys.path.insert(1, "../../../")
import h2o
import random

def milsong_checkpoint(ip,port):

    milsong_train = h2o.upload_file(h2o.locate("bigdata/laptop/milsongs/milsongs-train.csv.gz"))
    milsong_valid = h2o.upload_file(h2o.locate("bigdata/laptop/milsongs/milsongs-test.csv.gz"))
    distribution = "gaussian"

    # build first model
    ntrees1 = random.sample(range(50,100),1)[0]
    max_depth1 = random.sample(range(2,6),1)[0]
    min_rows1 = random.sample(range(10,16),1)[0]
    print "ntrees model 1: {0}".format(ntrees1)
    print "max_depth model 1: {0}".format(max_depth1)
    print "min_rows model 1: {0}".format(min_rows1)
    model1 = h2o.gbm(x=milsong_train[1:],y=milsong_train[0],ntrees=ntrees1,max_depth=max_depth1, min_rows=min_rows1,
                     distribution=distribution,validation_x=milsong_valid[1:],validation_y=milsong_valid[0])

    # save the model, then load the model
    model_path = h2o.save_model(model1, name="delete_model", force=True)
    restored_model = h2o.load_model(model_path)
    shutil.rmtree("delete_model")

    # continue building the model
    ntrees2 = ntrees1 + 50
    max_depth2 = max_depth1
    min_rows2 = min_rows1
    print "ntrees model 2: {0}".format(ntrees2)
    print "max_depth model 2: {0}".format(max_depth2)
    print "min_rows model 2: {0}".format(min_rows2)
    model2 = h2o.gbm(x=milsong_train[1:],y=milsong_train[0],ntrees=ntrees2,max_depth=max_depth2, min_rows=min_rows2,
                     distribution=distribution,validation_x=milsong_valid[1:],validation_y=milsong_valid[0],
                     checkpoint=restored_model._id)

    # build the equivalent of model 2 in one shot
    model3 = h2o.gbm(x=milsong_train[1:],y=milsong_train[0],ntrees=ntrees2,max_depth=max_depth2, min_rows=min_rows2,
                     distribution=distribution,validation_x=milsong_valid[1:],validation_y=milsong_valid[0])

if __name__ == "__main__":
    h2o.run_test(sys.argv, milsong_checkpoint)
