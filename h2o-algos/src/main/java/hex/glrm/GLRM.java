package hex.glrm;

import Jama.CholeskyDecomposition;
import Jama.Matrix;
import Jama.QRDecomposition;
import Jama.SingularValueDecomposition;

import hex.*;
import hex.gram.Gram;
import hex.gram.Gram.*;
import hex.kmeans.KMeans;
import hex.kmeans.KMeansModel;
import hex.pca.PCA;
import hex.pca.PCAModel;
import hex.schemas.GLRMV99;
import hex.glrm.GLRMModel.GLRMParameters;
import hex.schemas.ModelBuilderSchema;

import water.*;
import water.fvec.*;
import water.util.ArrayUtils;
import water.util.Log;
import water.util.RandomUtils;

import java.util.Arrays;
import java.util.Random;

/**
 * Generalized Low Rank Models
 * This is an algorithm for dimensionality reduction of a dataset. It is a general, parallelized
 * optimization algorithm that applies to a variety of loss and regularization functions.
 * Categorical columns are handled by expansion into 0/1 indicator columns for each level.
 * <a href = "http://web.stanford.edu/~boyd/papers/pdf/glrm.pdf">Generalized Low Rank Models</a>
 * @author anqi_fu
 */
public class GLRM extends ModelBuilder<GLRMModel,GLRMModel.GLRMParameters,GLRMModel.GLRMOutput> {
  // Convergence tolerance
  private final double TOLERANCE = 1e-6;

  // Maximum number of columns when categoricals expanded
  private final int MAX_COLS_EXPANDED = 5000;

  // Number of columns in training set (p)
  private transient int _ncolA;
  private transient int _ncolY;    // With categoricals expanded into 0/1 indicator cols

  // Number of columns in fitted X matrix (k)
  private transient int _ncolX;

  @Override public ModelBuilderSchema schema() {
    return new GLRMV99();
  }

  @Override public Job<GLRMModel> trainModelImpl(long work, boolean restartTimer) {
    return start(new GLRMDriver(), work, restartTimer);
  }

  @Override
  public long progressUnits() {
    return _parms._max_iterations + 1;
  }

  @Override public ModelCategory[] can_build() {
    return new ModelCategory[]{ModelCategory.Clustering};
  }

  @Override public BuilderVisibility builderVisibility() { return BuilderVisibility.Experimental; };

  public enum Initialization {
    Random, SVD, PlusPlus, User
  }

  // Called from an http request
  public GLRM(GLRMParameters parms) {
    super("GLRM", parms);
    init(false);
  }

  @Override public void init(boolean expensive) {
    super.init(expensive);
    if (_parms._loading_key == null) _parms._loading_key = Key.make("GLRMLoading_" + Key.rand());
    if (_parms._gamma_x < 0) error("_gamma_x", "gamma must be a non-negative number");
    if (_parms._gamma_y < 0) error("_gamma_y", "gamma_y must be a non-negative number");
    if (_parms._max_iterations < 1 || _parms._max_iterations > 1e6)
      error("_max_iterations", "max_iterations must be between 1 and 1e6 inclusive");
    if (_parms._init_step_size <= 0)
      error ("_init_step_size", "init_step_size must be a positive number");
    if (_parms._min_step_size < 0 || _parms._min_step_size > _parms._init_step_size)
      error("_min_step_size", "min_step_size must be between 0 and " + _parms._init_step_size);
    if (_parms._period <= 0) error("_period", "_period must be a positive integer");

    if (_train == null) return;
    if (_train.numCols() < 2) error("_train", "_train must have more than one column");

    // TODO: Initialize _parms._k = min(ncol(_train), nrow(_train)) if not set
    _ncolY = _train.numColsExp(true, false);
    int k_min = (int) Math.min(_ncolY, _train.numRows());
    if (_ncolY > MAX_COLS_EXPANDED)
      warn("_train", "_train has " + _ncolY + " columns when categoricals are expanded. Algorithm may be slow.");

    if (_parms._k < 1 || _parms._k > k_min) error("_k", "_k must be between 1 and " + k_min);
    if (null != _parms._user_points) { // Check dimensions of user-specified centers
      if (_parms._init != GLRM.Initialization.User)
        error("init", "init must be 'User' if providing user-specified points");

      if (_parms._user_points.get().numCols() != _train.numCols())
        error("_user_points", "The user-specified points must have the same number of columns (" + _train.numCols() + ") as the training observations");
      else if (_parms._user_points.get().numRows() != _parms._k)
        error("_user_points", "The user-specified points must have k = " + _parms._k + " rows");
      else {
        int zero_vec = 0;
        Vec[] centersVecs = _parms._user_points.get().vecs();
        for (int c = 0; c < _train.numCols(); c++) {
          if(centersVecs[c].naCnt() > 0) {
            error("_user_points", "The user-specified points cannot contain any missing values"); break;
          } else if(centersVecs[c].isConst() && centersVecs[c].max() == 0)
            zero_vec++;
        }
        if (zero_vec == _train.numCols())
          error("_user_points", "The user-specified points cannot all be zero");
      }
    }

    _ncolX = _parms._k;
    _ncolA = _train.numCols();
  }

  // Squared Frobenius norm of a matrix (sum of squared entries)
  public static double frobenius2(double[][] x) {
    if(x == null) return 0;

    double frob = 0;
    for(int i = 0; i < x.length; i++) {
      for(int j = 0; j < x[0].length; j++)
        frob += x[i][j] * x[i][j];
    }
    return frob;
  }

  // Transform each column of a 2-D array, assuming categoricals sorted before numeric cols
  public static double[][] transform(double[][] centers, double[] normSub, double[] normMul, int ncats, int nnums) {
    int K = centers.length;
    int N = centers[0].length;
    assert ncats + nnums == N;
    double[][] value = new double[K][N];
    double[] means = normSub == null ? MemoryManager.malloc8d(nnums) : normSub;
    double[] mults = normMul == null ? MemoryManager.malloc8d(nnums) : normMul;
    if(normMul == null) Arrays.fill(mults, 1.0);

    for (int clu = 0; clu < K; clu++) {
      System.arraycopy(centers[clu], 0, value[clu], 0, ncats);
      for (int col = 0; col < nnums; col++)
        value[clu][ncats+col] = (centers[clu][ncats+col] - means[col]) * mults[col];
    }
    return value;
  }

  // More efficient implementation assuming sdata cols aligned with adaptedFrame
  public static double[][] expandCats(double[][] sdata, DataInfo dinfo) {
    if(sdata == null || dinfo._cats == 0) return sdata;
    assert sdata[0].length == dinfo._adaptedFrame.numCols();

    // Column count for expanded matrix
    int catsexp = dinfo._catOffsets[dinfo._catOffsets.length-1];
    double[][] cexp = new double[sdata.length][catsexp + dinfo._nums];

    // Expand out categorical columns
    int cidx;
    for(int j = 0; j < dinfo._cats; j++) {
      for(int i = 0; i < sdata.length; i++) {
        if (Double.isNaN(sdata[i][j])) {
          if (dinfo._catMissing[j] == 0) continue;   // Skip if entry missing and no NA bucket. All indicators will be zero.
          else cidx = dinfo._catOffsets[j+1]-1;     // Otherwise, missing value turns into extra (last) factor
        } else
          cidx = dinfo.getCategoricalId(j, (int)sdata[i][j]);
        if(cidx >= 0) cexp[i][cidx] = 1;  // Ignore categorical levels outside domain
      }
    }

    // Copy over numeric columns
    for(int j = 0; j < dinfo._nums; j++) {
      for(int i = 0; i < sdata.length; i++)
        cexp[i][catsexp+j] = sdata[i][dinfo._cats+j];
    }
    return cexp;
  }

  class GLRMDriver extends H2O.H2OCountedCompleter<GLRMDriver> {

    // Initialize Y and X matrices
    // tinfo = original training data A, dinfo = [A,X,W] where W is working copy of X (initialized here)
    private double[][] initialXY(DataInfo tinfo, Frame dfrm, long na_cnt) {
      double[][] centers, centers_exp = null;
      Random rand = RandomUtils.getRNG(_parms._seed);

      if (null != _parms._user_points) { // Set Y = user-specified starting points, X = standard normal matrix
        Vec[] centersVecs = _parms._user_points.get().vecs();
        centers = new double[_parms._k][_ncolA];

        // Get the centers and put into array
        for (int c = 0; c < _ncolA; c++) {
          for (int r = 0; r < _parms._k; r++)
            centers[r][c] = centersVecs[c].at(r);
        }

        // Permute cluster columns to align with dinfo and expand out categoricals
        centers = ArrayUtils.permuteCols(centers, tinfo._permutation);
        centers_exp = expandCats(centers, tinfo);
        InitialXProj xtsk = new InitialXProj(_parms, _ncolA, _ncolX);
        xtsk.doAll(dfrm);
        return centers_exp;   // Don't project or change Y in any way if user-specified, just return it

      } else if (_parms._init == Initialization.Random) {  // Generate X and Y from standard normal distribution
        centers_exp = ArrayUtils.gaussianArray(_parms._k, _ncolY);
        InitialXProj xtsk = new InitialXProj(_parms, _ncolA, _ncolX);
        xtsk.doAll(dfrm);

      } else if (_parms._init == Initialization.SVD) {  // Run SVD on A'A/n (Gram) and set Y = right singular vectors
        PCAModel.PCAParameters parms = new PCAModel.PCAParameters();
        parms._train = _parms._train;
        parms._ignored_columns = _parms._ignored_columns;
        parms._ignore_const_cols = _parms._ignore_const_cols;
        parms._score_each_iteration = _parms._score_each_iteration;
        parms._use_all_factor_levels = true;   // Since GLRM requires Y matrix to have fully expanded ncols
        parms._k = _parms._k;
        parms._max_iterations = _parms._max_iterations;
        parms._transform = _parms._transform;
        parms._seed = _parms._seed;
        parms._pca_method = PCAModel.PCAParameters.Method.GramSVD;
        parms._impute_missing = true;

        PCAModel pca = null;
        PCA job = null;
        try {
          job = new PCA(parms);
          pca = job.trainModel().get();
        } finally {
          if (job != null) job.remove();
          if (pca != null) pca.remove();
        }

        // Ensure SVD centers align with adapted training frame cols
        assert pca._output._permutation.length == tinfo._permutation.length;
        for(int i = 0; i < tinfo._permutation.length; i++)
          assert pca._output._permutation[i] == tinfo._permutation[i];
        centers_exp = ArrayUtils.transpose(pca._output._eigenvectors_raw);
        // for(int i = 0; i < centers_exp.length; i++)
        //  ArrayUtils.mult(centers_exp[i], pca._output._std_deviation[i] * Math.sqrt(pca._output._nobs-1));
        InitialXProj xtsk = new InitialXProj(_parms, _ncolA, _ncolX);  // TODO: We want X = UD when Y = V' from SVD A = UDV'
        xtsk.doAll(dfrm);

      } else if (_parms._init == Initialization.PlusPlus) {  // Run k-means++ and set Y = resulting cluster centers, X = indicator matrix of assignments
        KMeansModel.KMeansParameters parms = new KMeansModel.KMeansParameters();
        parms._train = _parms._train;
        parms._ignored_columns = _parms._ignored_columns;
        parms._ignore_const_cols = _parms._ignore_const_cols;
        parms._score_each_iteration = _parms._score_each_iteration;
        parms._init = KMeans.Initialization.PlusPlus;
        parms._k = _parms._k;
        parms._max_iterations = _parms._max_iterations;
        parms._standardize = true;
        parms._seed = _parms._seed;
        parms._pred_indicator = true;

        KMeansModel km = null;
        KMeans job = null;
        try {
          job = new KMeans(parms);
          km = job.trainModel().get();

          // Score only if clusters well-defined and closed-form solution does not exist
          double frob = frobenius2(km._output._centers_raw);
          if(frob != 0 && !Double.isNaN(frob) && na_cnt == 0 && !_parms.hasClosedForm()) {
            // Frame pred = km.score(_parms.train());
            Log.info("Initializing X to matrix of indicator columns corresponding to cluster assignments");
            InitialXKMeans xtsk = new InitialXKMeans(_parms, km, _ncolA, _ncolX);
            xtsk.doAll(dfrm);
          }
        } finally {
          if (job != null) job.remove();
          if (km != null) km.remove();
        }

        // Permute cluster columns to align with dinfo, normalize nums, and expand out cats to indicator cols
        centers = ArrayUtils.permuteCols(km._output._centers_raw, tinfo.mapNames(km._output._names));
        centers = transform(centers, tinfo._normSub, tinfo._normMul, tinfo._cats, tinfo._nums);
        centers_exp = expandCats(centers, tinfo);
      } else
        error("_init", "Initialization method " + _parms._init + " is undefined");

      // If all centers are zero or any are NaN, initialize to standard Gaussian random matrix
      assert centers_exp != null && centers_exp.length == _parms._k && centers_exp[0].length == _ncolY : "Y must have " + _parms._k + " rows and " + _ncolY + " columns";
      double frob = frobenius2(centers_exp);   // TODO: Don't need to calculate twice if k-means++
      if(frob == 0 || Double.isNaN(frob)) {
        warn("_init", "Initialization failed. Setting initial Y to standard normal random matrix instead");
        centers_exp = ArrayUtils.gaussianArray(_parms._k, _ncolY);
      }

      // Project rows of Y into appropriate subspace for regularizer
      for(int i = 0; i < _parms._k; i++)
        centers_exp[i] = _parms.project_y(centers_exp[i], rand);
      return centers_exp;
    }

    // In case of L2 loss and regularization, initialize closed form X = AY'(YY' + \gamma)^(-1)
    private void initialXClosedForm(DataInfo dinfo, Archetypes yt_arch, double[] normSub, double[] normMul) {
      Log.info("Initializing X = AY'(YY' + gamma I)^(-1) where A = training data");
      double[][] ygram = ArrayUtils.formGram(yt_arch._archetypes);
      if (_parms._gamma_y > 0) {
        for(int i = 0; i < ygram.length; i++)
          ygram[i][i] += _parms._gamma_y;
      }
      CholeskyDecomposition yychol = regularizedCholesky(ygram, 10, false);
      if(!yychol.isSPD())
        Log.warn("Initialization failed: (YY' + gamma I) is non-SPD. Setting initial X to standard normal random matrix. Results will be numerically unstable");
      else {
        CholMulTask cmtsk = new CholMulTask(_parms, yychol, yt_arch, _ncolA, _ncolX, dinfo._cats, normSub, normMul);
        cmtsk.doAll(dinfo._adaptedFrame);
      }
    }

    // Stopping criteria
    private boolean isDone(GLRMModel model, int steps_in_row, double step) {
      if (!isRunning()) return true;  // Stopped/cancelled

      // Stopped for running out of iterations
      if (model._output._iterations >= _parms._max_iterations) return true;

      // Stopped for falling below minimum step size
      if (step <= _parms._min_step_size) return true;

      // Stopped when enough steps and average decrease in objective per iteration < TOLERANCE
      if (model._output._iterations > 10 && steps_in_row > 3 &&
              Math.abs(model._output._avg_change_obj) < TOLERANCE) return true;
      return false;       // Not stopping
    }

    // Regularized Cholesky decomposition using H2O implementation
    public Cholesky regularizedCholesky(Gram gram, int max_attempts) {
      int attempts = 0;
      double addedL2 = 0;   // TODO: Should I report this to the user?
      Cholesky chol = gram.cholesky(null);

      while(!chol.isSPD() && attempts < max_attempts) {
        if(addedL2 == 0) addedL2 = 1e-5;
        else addedL2 *= 10;
        ++attempts;
        gram.addDiag(addedL2); // try to add L2 penalty to make the Gram SPD
        Log.info("Added L2 regularization = " + addedL2 + " to diagonal of Gram matrix");
        gram.cholesky(chol);
      }
      if(!chol.isSPD())
        throw new Gram.NonSPDMatrixException();
      return chol;
    }
    public Cholesky regularizedCholesky(Gram gram) { return regularizedCholesky(gram, 10); }

    // Regularized Cholesky decomposition using JAMA implementation
    public CholeskyDecomposition regularizedCholesky(double[][] gram, int max_attempts, boolean throw_exception) {
      int attempts = 0;
      double addedL2 = 0;
      Matrix gmat = new Matrix(gram);
      CholeskyDecomposition chol = new CholeskyDecomposition(gmat);

      while(!chol.isSPD() && attempts < max_attempts) {
        if(addedL2 == 0) addedL2 = 1e-5;
        else addedL2 *= 10;
        ++attempts;

        for(int i = 0; i < gram.length; i++) gmat.set(i,i,addedL2); // try to add L2 penalty to make the Gram SPD
        Log.info("Added L2 regularization = " + addedL2 + " to diagonal of Gram matrix");
        chol = new CholeskyDecomposition(gmat);
      }
      if(!chol.isSPD() && throw_exception)
        throw new Gram.NonSPDMatrixException();
      return chol;
    }
    public CholeskyDecomposition regularizedCholesky(double[][] gram) { return regularizedCholesky(gram, 10, true); }

    // Recover singular values and eigenvectors of XY
    public void recoverSVD(GLRMModel model, DataInfo xinfo) {
      // NOTE: Gram computes X'X/n where n = nrow(A) = number of rows in training set
      GramTask xgram = new GramTask(self(), xinfo).doAll(xinfo._adaptedFrame);
      Cholesky xxchol = regularizedCholesky(xgram._gram);

      // R from QR decomposition of X = QR is upper triangular factor of Cholesky of X'X
      // Gram = X'X/n = LL' -> X'X = (L*sqrt(n))(L'*sqrt(n))
      Matrix x_r = new Matrix(xxchol.getL()).transpose();
      x_r = x_r.times(Math.sqrt(_train.numRows()));

      Matrix yt = new Matrix(model._output._archetypes);
      QRDecomposition yt_qr = new QRDecomposition(yt);
      Matrix yt_r = yt_qr.getR();   // S from QR decomposition of Y' = ZS
      Matrix rrmul = x_r.times(yt_r.transpose());
      SingularValueDecomposition rrsvd = new SingularValueDecomposition(rrmul);   // RS' = U \Sigma V'

      // Eigenvectors are V'Z' = (ZV)'
      Matrix eigvec = yt_qr.getQ().times(rrsvd.getV());
      model._output._eigenvectors = eigvec.getArray();

      // Singular values ordered in weakly descending order by algorithm
      model._output._singular_vals = rrsvd.getSingularValues();
    }

    @Override protected void compute2() {
      GLRMModel model = null;
      DataInfo dinfo = null, xinfo = null, tinfo = null;
      Frame fr = null, x = null;
      boolean overwriteX = false;

      try {
        init(true);   // Initialize parameters
        _parms.read_lock_frames(GLRM.this); // Fetch & read-lock input frames
        if (error_count() > 0) throw new IllegalArgumentException("Found validation errors: " + validationErrors());

        // The model to be built
        model = new GLRMModel(dest(), _parms, new GLRMModel.GLRMOutput(GLRM.this));
        model.delete_and_lock(self());

        // Save adapted frame info for scoring later
        tinfo = new DataInfo(Key.make(), _train, _valid, 0, true, _parms._transform, DataInfo.TransformType.NONE, false, false, false, /* weights */ false, /* offset */ false, /* fold */ false);
        DKV.put(tinfo._key, tinfo);

        // Save training frame adaptation information for use in scoring later
        model._output._normSub = tinfo._normSub == null ? new double[tinfo._nums] : tinfo._normSub;
        if(tinfo._normMul == null) {
          model._output._normMul = new double[tinfo._nums];
          Arrays.fill(model._output._normMul, 1.0);
        } else
          model._output._normMul = tinfo._normMul;
        model._output._permutation = tinfo._permutation;
        model._output._nnums = tinfo._nums;
        model._output._ncats = tinfo._cats;
        model._output._catOffsets = tinfo._catOffsets;
        model._output._names_expanded = tinfo.coefNames();

        long nobs = _train.numRows() * _train.numCols();
        long na_cnt = 0;
        for(int i = 0; i < _train.numCols(); i++)
          na_cnt += _train.vec(i).naCnt();
        model._output._nobs = nobs - na_cnt;   // TODO: Should we count NAs?

        // 0) Initialize Y and X matrices
        // Jam A and X into a single frame for distributed computation
        // [A,X,W] A is read-only training data, X is matrix from prior iteration, W is working copy of X this iteration
        fr = new Frame(_train);
        for (int i = 0; i < _ncolX; i++) fr.add("xcol_" + i, fr.anyVec().makeZero());
        for (int i = 0; i < _ncolX; i++) fr.add("wcol_" + i, fr.anyVec().makeZero());
        dinfo = new DataInfo(Key.make(), fr, null, 0, true, _parms._transform, DataInfo.TransformType.NONE, false, false, false, /* weights */ false, /* offset */ false, /* fold */ false);
        DKV.put(dinfo._key, dinfo);

        int weightId = dinfo._weights ? dinfo.weightChunkId() : -1;
        int[] numLevels = tinfo._adaptedFrame.numLevels();

        // Use closed form solution for X if L2 loss and regularization
        double[][] yinit = initialXY(tinfo, dinfo._adaptedFrame, na_cnt);
        Archetypes yt = new Archetypes(ArrayUtils.transpose(yinit), true, tinfo._catOffsets, numLevels);
        if (na_cnt == 0 && _parms.hasClosedForm())
          initialXClosedForm(dinfo, yt, model._output._normSub, model._output._normMul);

        // Compute initial objective function
        boolean regX = _parms._regularization_x != GLRMParameters.Regularizer.None && _parms._gamma_x != 0;  // Assume regularization on initial X is finite, else objective can be NaN if \gamma_x = 0
        ObjCalc objtsk = new ObjCalc(_parms, yt, _ncolA, _ncolX, dinfo._cats, model._output._normSub, model._output._normMul, weightId, regX).doAll(dinfo._adaptedFrame);
        model._output._objective = objtsk._loss + _parms._gamma_x * objtsk._xold_reg + _parms._gamma_y * _parms.regularize_y(yt._archetypes);
        model._output._iterations = 0;
        model._output._avg_change_obj = 2 * TOLERANCE;    // Run at least 1 iteration
        model.update(_key);  // Update model in K/V store
        update(1);           // One unit of work

        double step = _parms._init_step_size;   // Initial step size
        int steps_in_row = 0;                   // Keep track of number of steps taken that decrease objective

        while (!isDone(model, steps_in_row, step)) {
          // TODO: Should step be divided by number of original or expanded (with 0/1 categorical) cols?
          // 1) Update X matrix given fixed Y
          UpdateX xtsk = new UpdateX(_parms, yt, step/_ncolA, overwriteX, _ncolA, _ncolX, dinfo._cats, model._output._normSub, model._output._normMul, weightId);
          xtsk.doAll(dinfo._adaptedFrame);
          
          // 2) Update Y matrix given fixed X
          UpdateY ytsk = new UpdateY(_parms, yt, step/_ncolA, _ncolA, _ncolX, dinfo._cats, model._output._normSub, model._output._normMul, weightId);
          double[][] yttmp = ytsk.doAll(dinfo._adaptedFrame)._ytnew;
          Archetypes ytnew = new Archetypes(yttmp, true, dinfo._catOffsets, numLevels);

          // 3) Compute average change in objective function
          objtsk = new ObjCalc(_parms, ytnew, _ncolA, _ncolX, dinfo._cats, model._output._normSub, model._output._normMul, weightId).doAll(dinfo._adaptedFrame);
          double obj_new = objtsk._loss + _parms._gamma_x * xtsk._xreg + _parms._gamma_y * ytsk._yreg;
          model._output._avg_change_obj = (model._output._objective - obj_new) / nobs;
          model._output._iterations++;

          // step = 1.0 / model._output._iterations;   // Step size \alpha_k = 1/iters
          if(model._output._avg_change_obj > 0) {   // Objective decreased this iteration
            yt = ytnew;
            model._output._archetypes = yt._archetypes;
            model._output._objective = obj_new;
            step *= 1.05;
            steps_in_row = Math.max(1, steps_in_row+1);
            overwriteX = true;
          } else {    // If objective increased, re-run with smaller step size
            step = step / Math.max(1.5, -steps_in_row);
            steps_in_row = Math.min(0, steps_in_row-1);
            overwriteX = false;
            Log.info("Iteration " + model._output._iterations + ": Objective increased to " + obj_new + "; reducing step size to " + step);
          }
          model._output._step_size = step;
          model.update(self()); // Update model in K/V store
          update(1);            // One unit of work
        }

        // 4) Save solution to model output
        // Save X frame for user reference later
        Vec[] xvecs = new Vec[_ncolX];
        if(overwriteX) {
          for (int i = 0; i < _ncolX; i++) xvecs[i] = fr.vec(idx_xnew(i, _ncolA, _ncolX));
        } else {
          for (int i = 0; i < _ncolX; i++) xvecs[i] = fr.vec(idx_xold(i, _ncolA));
        }
        x = new Frame(_parms._loading_key, null, xvecs);
        xinfo = new DataInfo(Key.make(), x, null, 0, true, DataInfo.TransformType.NONE, DataInfo.TransformType.NONE, false, false, false, /* weights */ false, /* offset */ false, /* fold */ false);
        DKV.put(x._key, x);
        DKV.put(xinfo._key, xinfo);
        model._output._step_size = step;
        model._output._loading_key = _parms._loading_key;
        model._output._archetypes_full = yt;  // Need full archetypes object for scoring
        model._output._archetypes = yt._archetypes;
        if (_parms._recover_svd) recoverSVD(model, xinfo);

        model.update(self());
        done();
      } catch (Throwable t) {
        Job thisJob = DKV.getGet(_key);
        if (thisJob._state == JobState.CANCELLED) {
          Log.info("Job cancelled by user.");
        } else {
          t.printStackTrace();
          failed(t);
          throw t;
        }
      } finally {
        updateModelOutput();
        _parms.read_unlock_frames(GLRM.this);
        if (model != null) model.unlock(_key);
        if (tinfo != null) tinfo.remove();
        if (dinfo != null) dinfo.remove();
        if (xinfo != null) xinfo.remove();

        // if (x != null && !_parms._keep_loading) x.delete();
        // Clean up unused copy of X matrix
        if (fr != null) {
          if(overwriteX) {
            for (int i = 0; i < _ncolX; i++) fr.vec(idx_xold(i, _ncolA)).remove();
          } else {
            for (int i = 0; i < _ncolX; i++) fr.vec(idx_xnew(i, _ncolA, _ncolX)).remove();
          }
        }
      }
      tryComplete();
    }

    Key self() {
      return _key;
    }
  }

  protected static final class Archetypes extends Iced<Archetypes> {
    double[][] _archetypes;
    boolean _transposed;    // Is _archetypes = Y'? Used during model building for convenience.
    final int[] _catOffsets;
    final int[] _numLevels;

    Archetypes(double[][] y, boolean transposed, int[] catOffsets, int[] numLevels) {
      _archetypes = y;
      _transposed = transposed;
      _catOffsets = catOffsets;
      _numLevels = numLevels;   // TODO: Check sum(numLevels) + nnums == nfeatures()
    }

    public int rank() {
      return _transposed ? _archetypes[0].length : _archetypes.length;
    }

    public int nfeatures() {
      return _transposed ? _archetypes.length : _archetypes[0].length;
    }

    // If transpose = true, we want to return Y'
    public double[][] getY(boolean transpose) {
      return (transpose ^ _transposed) ? ArrayUtils.transpose(_archetypes) : _archetypes;
    }

    // For j = 0 to number of numeric columns - 1
    public int getNumCidx(int j) {
      return _catOffsets[_catOffsets.length-1]+j;
    }

    // For j = 0 to number of categorical columns - 1, and level = 0 to number of levels in categorical column - 1
    public int getCatCidx(int j, int level) {
      assert _numLevels[j] != 0 : "Number of levels in categorical column cannot be zero";
      assert !Double.isNaN(level) && level >= 0 && level < _numLevels[j] : "Got level = " + level + " when expected integer in [0," + _numLevels[j] + ")";
      return _catOffsets[j]+level;
    }

    protected final double getNum(int j, int k) {
      int cidx = getNumCidx(j);
      return _transposed ? _archetypes[cidx][k] : _archetypes[k][cidx];
    }

    protected final double[] getNumCol(int j) {
      int cidx = getNumCidx(j);
      if (_transposed) return _archetypes[cidx];
      double[] col = new double[rank()];
      for(int k = 0; k < col.length; k++)
        col[k] = _archetypes[k][cidx];
      return col;
    }

    // Inner product x * y_j where y_j is numeric column j of Y
    protected final double lmulNumCol(double[] x, int j) {
      assert x != null && x.length == rank() : "x must be of length " + rank();
      int cidx = getNumCidx(j);

      double prod = 0;
      if (_transposed) {
        for(int k = 0; k < rank(); k++)
          prod += x[k] * _archetypes[cidx][k];
      } else {
        for (int k = 0; k < rank(); k++)
          prod += x[k] * _archetypes[k][cidx];
      }
      return prod;
    }

    protected final double getCat(int j, int level, int k) {
      int cidx = getCatCidx(j, level);
      return _transposed ? _archetypes[cidx][k] : _archetypes[k][cidx];
    }

    // Extract Y_j the k by d_j block of Y corresponding to categorical column j
    // Note: d_j = number of levels in categorical column j
    protected final double[][] getCatBlock(int j) {
      assert _numLevels[j] != 0 : "Number of levels in categorical column cannot be zero";
      double[][] block = new double[rank()][_numLevels[j]];

      if (_transposed) {
        for (int level = 0; level < _numLevels[j]; level++) {
          int cidx = getCatCidx(j,level);
          for (int k = 0; k < rank(); k++)
            block[k][level] = _archetypes[cidx][k];
        }
      } else {
        for (int level = 0; level < _numLevels[j]; level++) {
          int cidx = getCatCidx(j,level);
          for (int k = 0; k < rank(); k++)
            block[k][level] = _archetypes[k][cidx];
        }
      }
      return block;
    }

    // Vector-matrix product x * Y_j where Y_j is block of Y corresponding to categorical column j
    protected final double[] lmulCatBlock(double[] x, int j) {
      assert _numLevels[j] != 0 : "Number of levels in categorical column cannot be zero";
      assert x != null && x.length == rank() : "x must be of length " + rank();
      double[] prod = new double[_numLevels[j]];

      if (_transposed) {
        for (int level = 0; level < _numLevels[j]; level++) {
          int cidx = getCatCidx(j,level);
          for (int k = 0; k < rank(); k++)
            prod[level] += x[k] * _archetypes[cidx][k];
        }
      } else {
        for (int level = 0; level < _numLevels[j]; level++) {
          int cidx = getCatCidx(j,level);
          for (int k = 0; k < rank(); k++)
            prod[level] += x[k] * _archetypes[k][cidx];
        }
      }
      return prod;
    }
  }

  // In chunk, first _ncolA cols are A, next _ncolX cols are X
  protected static int idx_xold(int c, int ncolA) { return ncolA+c; }
  protected static int idx_xnew(int c, int ncolA, int ncolX) { return ncolA+ncolX+c; }
  protected static Chunk chk_xold(Chunk chks[], int c, int ncolA) { return chks[ncolA+c]; }
  protected static Chunk chk_xnew(Chunk chks[], int c, int ncolA, int ncolX) { return chks[ncolA+ncolX+c]; }

  // Initialize X to standard Gaussian random matrix projected into regularizer subspace
  private static class InitialXProj extends MRTask<InitialXProj> {
    GLRMParameters _parms;
    final int _ncolA;         // Number of cols in training frame
    final int _ncolX;         // Number of cols in X (k)

    InitialXProj(GLRMParameters parms, int ncolA, int ncolX) {
      _parms = parms;
      _ncolA = ncolA;
      _ncolX = ncolX;
    }

    @Override public void map( Chunk chks[] ) {
      Random rand = RandomUtils.getRNG(_parms._seed + chks[0].start());

      for(int row = 0; row < chks[0]._len; row++) {
        double xrow[] = ArrayUtils.gaussianVector(_ncolX, _parms._seed);
        xrow = _parms.project_x(xrow, rand);
        for(int c = 0; c < xrow.length; c++) {
          chks[_ncolA+c].set(row, xrow[c]);
          chks[_ncolA+_ncolX+c].set(row, xrow[c]);
        }
      }
    }
  }

  // Initialize X to matrix of indicator columns for cluster assignments, e.g. k = 4, cluster = 3 -> [0, 0, 1, 0]
  private static class InitialXKMeans extends MRTask<InitialXKMeans> {
    GLRMParameters _parms;
    KMeansModel _model;
    final int _ncolA;         // Number of cols in training frame
    final int _ncolX;         // Number of cols in X (k)

    InitialXKMeans(GLRMParameters parms, KMeansModel model, int ncolA, int ncolX) {
      _parms = parms;
      _model = model;
      _ncolA = ncolA;
      _ncolX = ncolX;
    }

    @Override public void map( Chunk chks[] ) {
      double tmp [] = new double[_ncolA];
      double preds[] = new double[_ncolX];
      Random rand = RandomUtils.getRNG(_parms._seed + chks[0].start());

      for(int row = 0; row < chks[0]._len; row++) {
        double p[] = _model.score_indicator(chks, row, tmp, preds);
        p = _parms.project_x(p, rand);  // TODO: Should we restrict indicator cols to regularizer subspace?
        for(int c = 0; c < preds.length; c++) {
          chks[_ncolA+c].set(row, p[c]);
          chks[_ncolA+_ncolX+c].set(row, p[c]);
        }
      }
    }
  }

  private static class UpdateX extends MRTask<UpdateX> {
    // Input
    GLRMParameters _parms;
    final double _alpha;      // Step size divided by num cols in A
    final boolean _update;    // Should we update X from working copy?
    final Archetypes _yt;     // _yt = Y' (transpose of Y)
    final int _ncolA;         // Number of cols in training frame
    final int _ncolX;         // Number of cols in X (k)
    final int _ncats;         // Number of categorical cols in training frame
    final double[] _normSub;  // For standardizing training data
    final double[] _normMul;
    final int _weightId;

    // Output
    double _loss;    // Loss evaluated on A - XY using new X (and current Y)
    double _xreg;    // Regularization evaluated on new X

    UpdateX(GLRMParameters parms, Archetypes yt, double alpha, boolean update, int ncolA, int ncolX, int ncats, double[] normSub, double[] normMul, int weightId) {
      assert yt != null && yt.rank() == ncolX;
      _parms = parms;
      _yt = yt;
      _alpha = alpha;
      _update = update;
      _ncolA = ncolA;
      _ncolX = ncolX;

      // dinfo contains [A,X,W], but we only use its info on A (cols 1 to ncolA)
      assert ncats <= ncolA;
      _ncats = ncats;
      _weightId = weightId;
      _normSub = normSub;
      _normMul = normMul;
    }

    @Override public void map(Chunk[] cs) {
      assert (_ncolA + 2*_ncolX) == cs.length;
      double[] a = new double[_ncolA];
      Chunk chkweight = _weightId >= 0 ? cs[_weightId]:new C0DChunk(1,cs[0]._len);
      Random rand = RandomUtils.getRNG(_parms._seed + cs[0].start());
      _loss = _xreg = 0;

      for(int row = 0; row < cs[0]._len; row++) {
        double[] grad = new double[_ncolX];
        double[] xnew = new double[_ncolX];

        // Additional user-specified weight on loss for this row
        double cweight = chkweight.atd(row);
        assert !Double.isNaN(cweight) : "User-specified weight cannot be NaN";

        // Copy old working copy of X to current X if requested
        if(_update) {
          for(int k = 0; k < _ncolX; k++)
            chk_xold(cs,k,_ncolA).set(row, chk_xnew(cs,k,_ncolA,_ncolX).atd(row));
        }

        // Compute gradient of objective at row
        // Categorical columns
        for(int j = 0; j < _ncats; j++) {
          a[j] = cs[j].atd(row);
          if(Double.isNaN(a[j])) continue;   // Skip missing observations in row

          // Calculate x_i * Y_j where Y_j is sub-matrix corresponding to categorical col j
          // double[] xy = new double[_dinfo._catLvls[j].length];
          double[] xy = new double[_yt._numLevels[j]];
          for(int level = 0; level < xy.length; level++) {
            for(int k = 0; k < _ncolX; k++) {
              xy[level] += chk_xold(cs,k,_ncolA).atd(row) * _yt.getCat(j, level, k);
            }
          }

          // Gradient wrt x_i is matrix product \grad L_{i,j}(x_i * Y_j, A_{i,j}) * Y_j'
          double[] weight = _parms.mlgrad(xy, (int) a[j]);
          double[][] ysub = _yt.getCatBlock(j);
          for(int k = 0; k < _ncolX; k++) {
            for(int c = 0; c < weight.length; c++)
              grad[k] += cweight * weight[c] * ysub[k][c];
          }
        }

        // Numeric columns
        for(int j = _ncats; j < _ncolA; j++) {
          int js = j - _ncats;
          a[j] = cs[j].atd(row);
          if(Double.isNaN(a[j])) continue;   // Skip missing observations in row

          // Inner product x_i * y_j
          double xy = 0;
          for(int k = 0; k < _ncolX; k++)
            xy += chk_xold(cs,k,_ncolA).atd(row) * _yt.getNum(js, k);

          // Sum over y_j weighted by gradient of loss \grad L_{i,j}(x_i * y_j, A_{i,j})
          double weight = cweight * _parms.lgrad(xy, (a[j] - _normSub[js]) * _normMul[js]);
          for(int k = 0; k < _ncolX; k++)
            grad[k] += weight * _yt.getNum(js, k);
        }

        // Update row x_i of working copy with new values
        double[] u = new double[_ncolX];
        for(int k = 0; k < _ncolX; k++) {
          double xold = chk_xold(cs,k,_ncolA).atd(row);   // Old value of x_i
          u[k] = xold - _alpha * grad[k];
          // xnew[k] = _parms.rproxgrad_x(xold - _alpha * grad[k], _alpha);  // Proximal gradient
          // chk_xnew(cs,k,_ncolA,_ncolX).set(row, xnew[k]);
          // _xreg += _parms.regularize_x(xnew[k]);
        }
        xnew = _parms.rproxgrad_x(u, _alpha, rand);
        _xreg += _parms.regularize_x(xnew);
        for(int k = 0; k < _ncolX; k++)
          chk_xnew(cs,k,_ncolA,_ncolX).set(row,xnew[k]);

        // Compute loss function using new x_i
        // Categorical columns
        for(int j = 0; j < _ncats; j++) {
          if(Double.isNaN(a[j])) continue;   // Skip missing observations in row
          double[] xy = ArrayUtils.multVecArr(xnew, _yt.getCatBlock(j));
          _loss += _parms.mloss(xy, (int) a[j]);
        }

        // Numeric columns
        for(int j = _ncats; j < _ncolA; j++) {
          int js = j - _ncats;
          if(Double.isNaN(a[j])) continue;   // Skip missing observations in row
          double xy = _yt.lmulNumCol(xnew, js);
          _loss += _parms.loss(xy, (a[j] - _normSub[js]) * _normMul[js]);
        }
        _loss *= cweight;
      }
    }

    @Override public void reduce(UpdateX other) {
      _loss += other._loss;
      _xreg += other._xreg;
    }
  }

  private static class UpdateY extends MRTask<UpdateY> {
    // Input
    GLRMParameters _parms;
    final double _alpha;      // Step size divided by num cols in A
    final Archetypes _ytold;  // Old Y' matrix
    final int _ncolA;         // Number of cols in training frame
    final int _ncolX;         // Number of cols in X (k)
    final int _ncats;         // Number of categorical cols in training frame
    final double[] _normSub;  // For standardizing training data
    final double[] _normMul;
    final int _weightId;

    // Output
    double[][] _ytnew;  // New Y matrix
    double _yreg;       // Regularization evaluated on new Y

    UpdateY(GLRMParameters parms, Archetypes yt, double alpha, int ncolA, int ncolX, int ncats, double[] normSub, double[] normMul, int weightId) {
      assert yt != null && yt.rank() == ncolX;
      _parms = parms;
      _alpha = alpha;
      _ncolA = ncolA;
      _ncolX = ncolX;
      _ytold = yt;
      _yreg = 0;
      // _ytnew = new double[_ncolA][_ncolX];

      // dinfo contains [A,X,W], but we only use its info on A (cols 1 to ncolA)
      assert ncats <= ncolA;
      _ncats = ncats;
      _weightId = weightId;
      _normSub = normSub;
      _normMul = normMul;
    }

    @Override public void map(Chunk[] cs) {
      assert (_ncolA + 2*_ncolX) == cs.length;
      _ytnew = new double[_ytold.nfeatures()][_ncolX];
      Chunk chkweight = _weightId >= 0 ? cs[_weightId]:new C0DChunk(1,cs[0]._len);

      // Categorical columns
      for(int j = 0; j < _ncats; j++) {
        // Compute gradient of objective at column
        for(int row = 0; row < cs[0]._len; row++) {
          double a = cs[j].atd(row);
          if(Double.isNaN(a)) continue;   // Skip missing observations in column
          double cweight = chkweight.atd(row);
          assert !Double.isNaN(cweight) : "User-specified weight cannot be NaN";

          // Calculate x_i * Y_j where Y_j is sub-matrix corresponding to categorical col j
          // double[] xy = new double[_dinfo._catLvls[j].length];
          double[] xy = new double[_ytold._numLevels[j]];
          for(int level = 0; level < xy.length; level++) {
            for(int k = 0; k < _ncolX; k++) {
              xy[level] += chk_xnew(cs,k,_ncolA,_ncolX).atd(row) * _ytold.getCat(j,level,k);
            }
          }

          // Gradient for level p is x_i weighted by \grad_p L_{i,j}(x_i * Y_j, A_{i,j})
          double[] weight = _parms.mlgrad(xy, (int) a);
          for(int level = 0; level < xy.length; level++) {
            for(int k = 0; k < _ncolX; k++)
              _ytnew[_ytold.getCatCidx(j, level)][k] += cweight * weight[level] * chk_xnew(cs,k,_ncolA,_ncolX).atd(row);
          }
        }
      }

      // Numeric columns
      for(int j = _ncats; j < _ncolA; j++) {
        int js = j - _ncats;
        int yidx = _ytold.getNumCidx(js);

        // Compute gradient of objective at column
        for(int row = 0; row < cs[0]._len; row++) {
          double a = cs[j].atd(row);
          if(Double.isNaN(a)) continue;   // Skip missing observations in column

          // Additional user-specified weight on loss for this row
          double cweight = chkweight.atd(row);
          assert !Double.isNaN(cweight) : "User-specified weight cannot be NaN";

          // Inner product x_i * y_j
          double xy = 0;
          for(int k = 0; k < _ncolX; k++)
            xy += chk_xnew(cs,k,_ncolA,_ncolX).atd(row) * _ytold.getNum(js,k);

          // Sum over x_i weighted by gradient of loss \grad L_{i,j}(x_i * y_j, A_{i,j})
          double weight = cweight * _parms.lgrad(xy, (a - _normSub[js]) * _normMul[js]);
          for(int k = 0; k < _ncolX; k++)
            _ytnew[yidx][k] += weight * chk_xnew(cs,k,_ncolA,_ncolX).atd(row);
        }
      }
    }

    @Override public void reduce(UpdateY other) {
      ArrayUtils.add(_ytnew, other._ytnew);
    }

    @Override protected void postGlobal() {
      assert _ytnew.length == _ytold.nfeatures() && _ytnew[0].length == _ytold.rank();
      Random rand = RandomUtils.getRNG(_parms._seed);

      // Compute new y_j values using proximal gradient
      for(int j = 0; j < _ytnew.length; j++) {
        double[] u = new double[_ytnew[0].length];
        for(int k = 0; k < _ytnew[0].length; k++) {
          // double u = _ytold[j][k] - _alpha * _ytnew[j][k];
          // _ytnew[j][k] = _parms.rproxgrad_y(u, _alpha);
          // _yreg += _parms.regularize_y(_ytnew[j][k]);
          u[k] = _ytold._archetypes[j][k] - _alpha * _ytnew[j][k];
        }
        _ytnew[j] = _parms.rproxgrad_y(u, _alpha, rand);
        _yreg += _parms.regularize_y(_ytnew[j]);
      }
    }
  }

  // Calculate the sum loss function in the optimization objective
  private static class ObjCalc extends MRTask<ObjCalc> {
    // Input
    GLRMParameters _parms;
    final Archetypes _yt;     // _yt = Y' (transpose of Y)
    final int _ncolA;         // Number of cols in training frame
    final int _ncolX;         // Number of cols in X (k)
    final int _ncats;         // Number of categorical cols in training frame
    final double[] _normSub;  // For standardizing training data
    final double[] _normMul;
    final int _weightId;
    final boolean _regX;      // Should I calculate regularization of (old) X matrix?

    // Output
    double _loss;
    double _xold_reg;

    ObjCalc(GLRMParameters parms, Archetypes yt, int ncolA, int ncolX, int ncats, double[] normSub, double[] normMul, int weightId) {
      this(parms, yt, ncolA, ncolX, ncats, normSub, normMul, weightId, false);
    }
    ObjCalc(GLRMParameters parms, Archetypes yt, int ncolA, int ncolX, int ncats, double[] normSub, double[] normMul, int weightId, boolean regX) {
      assert yt != null && yt.rank() == ncolX;
      assert ncats <= ncolA;
      _parms = parms;
      _yt = yt;
      _ncolA = ncolA;
      _ncolX = ncolX;
      _ncats = ncats;
      _regX = regX;
      _loss = _xold_reg = 0;

      _weightId = weightId;
      _normSub = normSub;
      _normMul = normMul;
    }

    @Override public void map(Chunk[] cs) {
      assert (_ncolA + 2*_ncolX) == cs.length;
      Chunk chkweight = _weightId >= 0 ? cs[_weightId]:new C0DChunk(1,cs[0]._len);

      for(int row = 0; row < cs[0]._len; row++) {
        // Additional user-specified weight on loss for this row
        double cweight = chkweight.atd(row);
        assert !Double.isNaN(cweight) : "User-specified weight cannot be NaN";

        // Categorical columns
        for(int j = 0; j < _ncats; j++) {
          double a = cs[j].atd(row);
          if (Double.isNaN(a)) continue;   // Skip missing observations in row

          // Calculate x_i * Y_j where Y_j is sub-matrix corresponding to categorical col j
          // double[] xy = new double[_dinfo._catLvls[j].length];
          double[] xy = new double[_yt._numLevels[j]];
          for(int level = 0; level < xy.length; level++) {
            for(int k = 0; k < _ncolX; k++) {
              xy[level] += chk_xnew(cs,k,_ncolA,_ncolX).atd(row) * _yt.getCat(j, level, k);
            }
          }
          _loss += _parms.mloss(xy, (int)a);
        }

        // Numeric columns
        for(int j = _ncats; j < _ncolA; j++) {
          double a = cs[j].atd(row);
          if (Double.isNaN(a)) continue;   // Skip missing observations in row

          // Inner product x_i * y_j
          double xy = 0;
          int js = j - _ncats;
          for(int k = 0; k < _ncolX; k++)
            xy += chk_xnew(cs,k,_ncolA,_ncolX).atd(row) * _yt.getNum(js, k);
          _loss += _parms.loss(xy, (a - _normSub[js]) * _normMul[js]);
        }
        _loss *= cweight;

        // Calculate regularization term for old X if requested
        if(_regX) {
          int idx = 0;
          double[] xrow = new double[_ncolX];
          for(int j = _ncolA; j < _ncolA+_ncolX; j++) {
            // double x = cs[j].atd(row);
            // _xold_reg += _parms.regularize_x(x);
            xrow[idx] = cs[j].atd(row);
            idx++;
          }
          assert idx == _ncolX;
          _xold_reg += _parms.regularize_x(xrow);
        }
      }
    }
  }

  // Solves XD = AY' for X where A is n by p, Y is k by p, D is k by k, and n >> p > k
  // Resulting matrix X = (AY')D^(-1) will have dimensions n by k
  private static class CholMulTask extends MRTask<CholMulTask> {
    GLRMParameters _parms;
    final Archetypes _yt;     // _yt = Y' (transpose of Y)
    final int _ncolA;         // Number of cols in training frame
    final int _ncolX;         // Number of cols in X (k)
    final int _ncats;         // Number of categorical cols in training frame
    final double[] _normSub;  // For standardizing training data
    final double[] _normMul;
    CholeskyDecomposition _chol;   // Cholesky decomposition of D = D', since we solve D'X' = DX' = AY'

    CholMulTask(GLRMParameters parms, CholeskyDecomposition chol, Archetypes yt, int ncolA, int ncolX, int ncats, double[] normSub, double[] normMul) {
      assert yt != null && yt.rank() == ncolX;
      assert ncats <= ncolA;
      _parms = parms;
      _yt = yt;
      _ncolA = ncolA;
      _ncolX = ncolX;
      _ncats = ncats;
      _chol = chol;

      _normSub = normSub;
      _normMul = normMul;
    }

    // [A,X,W] A is read-only training data, X is left matrix in A = XY decomposition, W is working copy of X
    @Override public void map(Chunk[] cs) {
      assert (_ncolA + 2*_ncolX) == cs.length;
      double[] xrow = new double[_ncolX];

      for(int row = 0; row < cs[0]._len; row++) {
        // 1) Compute single row of AY'
        for (int k = 0; k < _ncolX; k++) {
          // Categorical columns
          double x = 0;
          for(int d = 0; d < _ncats; d++) {
            double a = cs[d].atd(row);
            if (Double.isNaN(a)) continue;
            x += _yt.getCat(d, (int)a, k);
          }

          // Numeric columns
          for (int d = _ncats; d < _ncolA; d++) {
            int ds = d - _ncats;
            double a = cs[d].atd(row);
            if (Double.isNaN(a)) continue;
            x += (a - _normSub[ds]) * _normMul[ds] * _yt.getNum(ds, k);
          }
          xrow[k] = x;
        }

        // 2) Cholesky solve for single row of X
        // _chol.solve(xrow);
        Matrix tmp = _chol.solve(new Matrix(new double[][] {xrow}).transpose());
        xrow = tmp.getColumnPackedCopy();

        // 3) Save row of solved values into X (and copy W = X)
        int i = 0;
        for(int d = _ncolA; d < _ncolA+_ncolX; d++) {
          cs[d].set(row, xrow[i]);
          cs[d+_ncolX].set(row, xrow[i++]);
        }
        assert i == xrow.length;
      }
    }
  }
}
