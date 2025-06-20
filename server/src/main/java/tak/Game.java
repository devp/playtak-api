/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package tak;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.Random;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import tak.DTOs.GameDto;
import tak.FlowMessages.GameUpdate;
import tak.utils.ConcurrentHashSet;
import java.util.concurrent.locks.*;

/**
 *
 * @author chaitu
 */
public class Game implements Publisher<GameUpdate>{

	Player white;
	Player black;
	long time;

	int no;

	/**
	 * The uid of the seek that this game was created from.
	 * The Seek-API returns this when a seek is created.
	 * GameUpdate events can then be related to the seek.
	 */
	final Integer pntId;

	//time in milli seconds
	long originalTime;
	long incrementTime;
	long whiteTime;
	long blackTime;
	long lastUpdateTime;
	boolean timerStarted;
	long logOutWhite;
	long logOutBlack;
	static Timer timer = new Timer();
	public Lock gameLock;
	TimerTask outOfTime;

	Player drawOfferedBy;
	Player undoRequestedBy;

	boolean abandoned;

	ConcurrentHashSet<Player> spectators;
	protected ConcurrentHashSet<Subscriber<? super GameUpdate>> subscribers = new ConcurrentHashSet<>();

	public enum gameS {WHITE_ROAD, BLACK_ROAD, WHITE_TILE, BLACK_TILE, DRAW,
						WHITE, BLACK, ABORT, NONE};
	List<String> moveList;
	gameS gameState;

	static Map<Integer, Game> games = new ConcurrentHashMap<>();
	static ConcurrentHashSet<Player> gameListeners = new ConcurrentHashSet<>();

	public static int reconnectionTime;
	
	int capCount;
	int tileCount;
	int komi;
	int unrated;
	int tournament;
	int triggerMove;
	int timeAmount;
	int playerWhiteMoveCount;
	int playerBlackMoveCount;
	boolean isBotGame;

	class Board {
		int boardSize;
		int moveCount;
		int lastResetMove;
		int whiteCapstones;
		int blackCapstones;

		int whiteTilesCount;
		int blackTilesCount;

		class Square {
			private int file, row;
			private ArrayList<Character> stack;
			int graphNo;

			Square(int f, int r) {
				stack = new ArrayList<>();
				file = f;
				row = r;
				graphNo = -1;
			}

			@Override
			public Square clone() {
				Square sq = new Square(file, row);
				sq.graphNo = graphNo;
				for(Character c: stack) {
					sq.add(c);
				}
				return sq;
			}
			boolean isEmpty() {
				return stack.isEmpty();
			}
			void add(char c) {
				stack.add(c);
			}
			int size() {
				return stack.size();
			}
			char get(int i) {
				return stack.get(i);
			}
			char pop() {
				if(stack.size() > 0) {
					char ret = topOfStack();
					stack.remove(stack.size()-1);
					return ret;
				}
				return 0;
			}
			char topOfStack() {
				if(stack.isEmpty())
					return 0;

				return stack.get(stack.size()-1);
			}
			@Override
			public String toString() {
				return ((char)(file+'A'))+""+row+" "+graphNo;
			}

			public String stackString() {
				return stack.toString();
			}
		}
		Square[][] squares;

		Board(int b) {
			if (b < 3 || b > 8) {
				b = DEFAULT_SIZE;
			}

			boardSize = b;

			whiteCapstones = blackCapstones = capCount;
			whiteTilesCount = blackTilesCount = tileCount;
			
			moveCount = 0;
			lastResetMove=0;

			squares = new Square[boardSize][boardSize];
			for (int i = 0; i < b; i++) {
				for (int j = 0; j < b; j++) {
					squares[i][j] = new Square(j, i+1);
				}
			}
		}

		Board(int boardSize, int moveCount, int whiteCapstones,
				int blackCapstones, int whiteTilesCount, int blackTilesCount,
				Square[][] squares, int lastResetMove) {
			this.boardSize = boardSize;
			this.moveCount = moveCount;
			this.whiteCapstones = whiteCapstones;
			this.blackCapstones = blackCapstones;
			this.whiteTilesCount = whiteTilesCount;
			this.blackTilesCount = blackTilesCount;
			this.squares = squares;
			this.lastResetMove=lastResetMove;
		}

		@Override
		public Board clone() {
			Board clone = new Board(boardSize, moveCount,
								whiteCapstones, blackCapstones,
								whiteTilesCount, blackTilesCount,
								getClonedSquares(), lastResetMove);

			return clone;
		}

		Square[][] getClonedSquares() {
			Square[][] clone = new Square[boardSize][boardSize];

			for(int i=0;i<boardSize;i++){
				for(int j=0;j<boardSize;j++) {
					clone[i][j] = squares[i][j].clone();
				}
			}
			return clone;
		}

		private Square getSquare(char file, int rank) {
			if(!boundsCheck(file, rank))
				return null;
			return board.squares[rank-1][file-'A'];
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append("wc=").append(whiteCapstones).append(" bc=")
					.append(blackCapstones).append(" wt=")
					.append(whiteTilesCount).append(" bt=")
					.append(blackTilesCount).append(" mc=")
					.append(moveCount);
			sb.append("\n").append(getBoardString());
			return sb.toString();
		}

		public String getBoardString() {
			StringBuilder sb = new StringBuilder();
			for(int i=boardSize;i>0;i--){
				for(char j='A';j<boardSize+'A';j++){
					sb.append(getSquare(j, i).stackString()).append(" ");
				}
				sb.append("\n");
			}
			return sb.toString();
		}
	}

	Board board;
	Stack<Board> boardHistory;

	static int DEFAULT_SIZE = 5;

	static final char FLAT='f';
	static final char WALL='w';
	static final char CAPSTONE='c';

	/**
	 *
	 * @param p1: player who accepted the Seek
	 * @param p2: player who posted the seek
	 * @param b: board size
	 * @param t: time in seconds
	 * @param clr: color choice of p2
	 * @param triggerMove: move number to trigger time amount to add
	 * @param timeAmount: amount of time to add from trigger move
	 * @param pntId: ID of the Playtak Native Tournament game this game is related to. Use `null` if not related to PNT.
	 */
	Game(Player p1, Player p2, int b, int t, int i, Seek.COLOR clr, int komi, int pieces, int capstones, int unrated, int tournament, int triggerMove, int timeAmount, Integer pntId) {
		gameLock = new ReentrantLock();
		gameLock.lock();
		try{
			int rand = new Random().nextInt(2);
			this.pntId = pntId;
			this.komi = komi;
			tileCount = pieces;
			capCount = capstones;
			this.unrated = unrated;
			this.tournament = tournament;
			this.triggerMove = triggerMove;
			this.timeAmount = timeAmount * 1000;
			this.playerWhiteMoveCount = 0;
			this.playerBlackMoveCount = 0;
			this.isBotGame = p1.isbot || p2.isbot;
			if(clr == Seek.COLOR.ANY) {
				white = (rand==0)?p1:p2;
				black = (rand==0)?p2:p1;
			} else {
				white = (clr == Seek.COLOR.WHITE)?p2:p1;
				black = (clr == Seek.COLOR.WHITE)?p1:p2;
			}

			if(black.isbot){
				TimerTask tt=new TimerTask() {
					@Override
					public void run() {
						gameLock.lock();
						try{
							if(board.moveCount==0 && gameState==gameS.NONE){
								gameState = gameS.BLACK;
								whenGameEnd();
								timeCleanup();
							}
						}
						finally{
							gameLock.unlock();
						}
					}
				};
				timer.schedule(tt,300*1000);
			}

			originalTime = whiteTime = blackTime = t*1000;
			incrementTime = i*1000;

			timerStarted = false;
			logOutWhite = 0;
			logOutBlack = 0;

			time = System.currentTimeMillis();

			abandoned = false;

			board = new Board(b);
			boardHistory = new Stack<>();

			gameState = gameS.NONE;
			drawOfferedBy = null;
			undoRequestedBy = null;

			moveList = Collections.synchronizedList(new ArrayList<String>());

			boardHistory.push(board.clone());//store empty position
			spectators = new ConcurrentHashSet<>();

			insertEmpty();
		}
		finally{
			gameLock.unlock();
		}
	}

	static void addGame(Game g) {
		g.gameLock.lock();
		try{
			Game.games.put(g.no, g);
			Game.updateGameListListeners("Add " + g.stringForm());
		}
		finally{
			g.gameLock.unlock();
		}
	}

	static void removeGame(Game g) {
		g.gameLock.lock();
		try{
			Game.updateGameListListeners("Remove "+g.stringForm());
			Game.games.remove(g.no);
		}
		finally{
			g.gameLock.unlock();
		}
	}

	void newSpectator(Player p) {
		gameLock.lock();
		try{
			p.send("Observe "+stringForm());
			sendMoveListTo(p);
			spectators.add(p);
			updateTime(p);
		}
		finally{
			gameLock.unlock();
		}
	}

	void resign(Player p) {
		gameLock.lock();
		try{
			if(p == white)
				gameState = gameS.BLACK;
			else
				gameState = gameS.WHITE;
			whenGameEnd();
		}
		finally{
			gameLock.unlock();
		}
	}

	void saveBoardPosition() {
		gameLock.lock();
		try{
			boardHistory.push(board.clone());
		}
		finally{
			gameLock.unlock();
		}
	}

	void undoPosition() {
		gameLock.lock();
		try{
			boardHistory.pop();//discard cur pos
			board = boardHistory.peek().clone();//replace cur pos with top of stack

			moveList.remove(moveList.size()-1);
		}
		finally{
			gameLock.unlock();
		}
	}

	void undo(Player p) {
		gameLock.lock();
		try{
			if(board.moveCount <= 0) {
				p.sendNOK();
				return;
			}

			if(undoRequestedBy == null) {
				undoRequestedBy = p;
				Player otherPlayer = (p==white)?black:white;
				otherPlayer.sendWithoutLogging("Game#"+no+" RequestUndo");
			} else if(undoRequestedBy != p) {
				// logic is backwards which is why its not whites turn but removing white move count
				if(!isWhitesTurn()) {
					this.playerWhiteMoveCount--;
				} else {
					this.playerBlackMoveCount--;
				}
				updateTimeTurnChange();
				undoRequestedBy = null;
				undoPosition();
				updateOutOfTime();
				white.send("Game#"+no+" Undo");
				black.send("Game#"+no+" Undo");
				sendToSpectators("Game#"+no+" Undo");
			}
		}
		finally{
			gameLock.unlock();
		}
	}

	void removeUndo(Player p) {
		gameLock.lock();
		try{
			if(undoRequestedBy == p) {
				undoRequestedBy = null;
				Player otherPlayer = (p==white)?black:white;
				otherPlayer.sendWithoutLogging("Game#"+no+" RemoveUndo");
			}
		}
		finally{
			gameLock.unlock();
		}
	}

	void draw(Player p) {
		gameLock.lock();
		try{
			if(drawOfferedBy == null) {
				drawOfferedBy = p;
				Player otherPlayer = (p==white)?black:white;
				otherPlayer.sendWithoutLogging("Game#"+no+" OfferDraw");
			} else if(drawOfferedBy!=p) {
				gameState = gameS.DRAW;
				whenGameEnd();
			}
		}
		finally{
			gameLock.unlock();
		}
	}

	void removeDraw(Player p) {
		gameLock.lock();
		try{
			if(drawOfferedBy == p) {
				drawOfferedBy = null;
				Player otherPlayer = (p==white)?black:white;
				otherPlayer.sendWithoutLogging("Game#"+no+" RemoveDraw");
			}
		}
		finally{
			gameLock.unlock();
		}
	}

	void unSpectate(Player p) {
		gameLock.lock();
		try{
			spectators.remove(p);
		}
		finally{
			gameLock.unlock();
		}
	}

	static void sendGameListTo(Player p) {
		for (Integer no : Game.games.keySet()) {
			p.sendWithoutLogging("GameList Add "+Game.games.get(no).stringForm());
		}
	}

	void sendMoveListTo(Player p) {
		gameLock.lock();
		try{
			for(String move:moveList)
				p.sendWithoutLogging("Game#"+no+" "+move);
		}
		finally{
			gameLock.unlock();
		}
	}
	
	String stringForm() {
		gameLock.lock();
		try{
			StringBuilder sb = new StringBuilder(no+"");
			sb.append(" ").append(white.getName());
			sb.append(" ").append(black.getName());
			sb.append(" ").append(board.boardSize);
			sb.append(" ").append(originalTime/1000);
			sb.append(" ").append(incrementTime/1000);
			sb.append(" ").append(komi);
			sb.append(" ").append(tileCount);
			sb.append(" ").append(capCount);
			sb.append(" ").append(unrated);
			sb.append(" ").append(tournament);
			sb.append(" ").append(triggerMove);
			sb.append(" ").append(timeAmount/1000);
			return sb.toString();
		}
		finally{
			gameLock.unlock();
		}
	}

	public GameDto toDto() {
		gameLock.lock();
		var result = gameStateString();
		try{
			return GameDto.builder()
				.id(no)
				.pntId(pntId)
				.white(white.getName())
				.black(black.getName())
				.komi(komi / 2.f)
				.boardSize(board.boardSize)
				.capstones(capCount)
				.pieces(tileCount)
				.unrated(unrated > 0)
				.tournament(tournament > 0)
				.timeContingent((int)(originalTime / 1000))
				.timeIncrement((int)(incrementTime / 1000))
				.extraTimeAmount((int)(timeAmount / 1000))
				.extraTimeTriggerMove(triggerMove)
				.moves(moveList.toArray(String[]::new))
				.result(result == "---" ? null : result)
				.build();
		}
		finally{
			gameLock.unlock();
		}
	}

	static void registerGameListListener(Player p) {
		gameListeners.add(p);
		sendGameListTo(p);
	}

	static void unregisterGameListListener(Player p) {
		gameListeners.remove(p);
	}

	static void updateGameListListeners(final String st) {
		for (Player p : gameListeners) {
			p.sendWithoutLogging("GameList " + st);
		}
	}

	private boolean boundsCheck(char file, int rank) {
		int fl = file - 'A';
		int rk = rank - 1;
		return fl<board.boardSize && fl>=0 && rk<board.boardSize && rk>=0;
	}

	private boolean isWhitesTurn() {
		return board.moveCount%2 == 0;
	}

	private boolean turnOf(Player p) {
		boolean whiteTurn = isWhitesTurn();
		return ((p==white)==whiteTurn)||((p==black)==!whiteTurn);
	}

	String sqState(char file, int rank) {
		gameLock.lock();
		try{
			Board.Square sq = board.getSquare(file, rank);
			if(sq==null)
				return "[]";
			return sq.stackString();
		}
		finally{
			gameLock.unlock();
		}
	}

	private void checkOutOfPieces() {
		if(gameState!=gameS.NONE)
			return;
		if((board.whiteTilesCount==0 && board.whiteCapstones==0) ||
				(board.blackTilesCount==0 && board.blackCapstones==0)) {
			findWhoWon();
		}
	}

	private void timeCleanup() {
		Game.removeGame(this);
		white.removeGame();
		black.removeGame();
	}

	private void updateTime(Player p) {
		justUpdateTime();
		try{
			String msg="Game#"+no+" Time "+Math.max(whiteTime/1000,0L)+" "+Math.max(blackTime/1000,0L);
			if(p.client.protocolVersion>=1){
				msg="Game#"+no+" Timems "+Math.max(whiteTime,0L)+" "+Math.max(blackTime,0L);
			}
			p.sendWithoutLogging(msg);
		}
		catch(NullPointerException npe){
			
		}
	}
	
	private void sendTimeToAll(){
		String msg="Game#"+no+" Time "+Math.max(whiteTime/1000,0L)+" "+Math.max(blackTime/1000,0L);
		String msgms="Game#"+no+" Timems "+Math.max(whiteTime,0L)+" "+Math.max(blackTime,0L);
		try{
			if(white.client.protocolVersion>=1){
				white.sendWithoutLogging(msgms);
			}
			else{
				white.sendWithoutLogging(msg);
			}
		}
		catch(NullPointerException npe){
			
		}
		try{
			if(black.client.protocolVersion>=1){
				black.sendWithoutLogging(msgms);
			}
			else{
				black.sendWithoutLogging(msg);
			}
		}
		catch(NullPointerException npe){
			
		}
		for(Player p:spectators){
			try{
				if(p.client.protocolVersion>=1){
					p.sendWithoutLogging(msgms);
				}
				else{
					p.sendWithoutLogging(msg);
				}
			}
			catch(NullPointerException npe){
			
			}
		}
	}
	
	private void justUpdateTime(){
		if(board.moveCount == 0 || gameState!=gameS.NONE){
			return;
		}
		long curTime = System.nanoTime();
		long elapsedMS = (curTime - lastUpdateTime)/1000000;
		if(!isWhitesTurn()) {
			blackTime -= elapsedMS;
		} else {
			whiteTime -= elapsedMS;
		}

		lastUpdateTime = curTime;
	}
	
	void updateOutOfTime(){
		gameLock.lock();
		try{
			if(outOfTime!=null){
				outOfTime.cancel();
			}
			if(gameState!=gameS.NONE){
				return;
			}
			justUpdateTime();
			if(whiteTime<=0){
				gameState = gameS.BLACK;
				sendTimeToAll();
				whenGameEnd();
				timeCleanup();
				return;
			}
			else if(blackTime<=0){
				gameState = gameS.WHITE;
				sendTimeToAll();
				whenGameEnd();
				timeCleanup();
				return;
			}
			
			long timeToCount=0;
			if(isWhitesTurn()){
				timeToCount=whiteTime;
			}
			else{
				timeToCount=blackTime;
			}
			outOfTime=new TimerTask() {
				@Override
				public void run() {
					updateOutOfTime();
				}
			};
			timer.schedule(outOfTime,timeToCount);
		}
		finally{
			gameLock.unlock();
		}
	}

	private void updateTimeTurnChange() {
		if(gameState!=gameS.NONE){
			return;
		}
		//start time after 1st move is played
		if(!timerStarted) {
			timerStarted = true;
			this.lastUpdateTime = System.nanoTime();
		}
		
		if(!isWhitesTurn()) {
			blackTime += incrementTime;
			// Add time once trigger move is met
			if(this.playerBlackMoveCount == this.triggerMove) {
				blackTime += this.timeAmount;
				this.playerBlackMoveCount++;
			}
		}
		else{
			whiteTime += incrementTime;
			// Add time once trigger move is met
			if(this.playerWhiteMoveCount == this.triggerMove) {
				whiteTime += this.timeAmount;
				this.playerWhiteMoveCount++;
			}
		}

		justUpdateTime();
		sendTimeToAll();
	}

	Status placeMove(Player p, char file, int rank, boolean capstone, boolean wall) {
		gameLock.lock();
		try{
			Board.Square sq = board.getSquare(file, rank);
			if(!turnOf(p))
				return new Status("Not your turn", false);

			if(sq!=null && sq.isEmpty()) {
				char ch;
				if(capstone)
					ch = CAPSTONE;
				else if(wall)
					ch = WALL;
				else
					ch = FLAT;

				//First move should always be a flat
				if(board.moveCount/2 == 0 && !isFlat(ch))
					return new Status("First move should be flat", false);

				//White places with capitals
				if(isWhitesTurn())
					ch = (char)(ch - 'a'+'A');

				//first moves should be played with opponent pieces
				if(board.moveCount/2 == 0)
					ch = Character.isUpperCase(ch)?Character.toLowerCase(ch):
							Character.toUpperCase(ch);

				//check if enough capstones, and decrement if there are
				if(isCapstone(ch)) {
					int caps = isWhitesTurn()?board.whiteCapstones:board.blackCapstones;
					if(caps == 0)
						return new Status("You're out of capstones", false);
					if(isWhitesTurn())
						board.whiteCapstones--;
					else
						board.blackCapstones--;
				} else {
					if(isWhitesTurn())
						board.whiteTilesCount--;
					else
						board.blackTilesCount--;
				}

				sq.add(ch);
				// Add move count tracker add time trigger
				// the logic is backwards which is why it's not whites turn but adding to whites move count
				if(isWhitesTurn()) {
					this.playerWhiteMoveCount++;
				} else {
					this.playerBlackMoveCount++;
				}
				updateTimeTurnChange();
				board.moveCount++;
				updateOutOfTime();
				board.lastResetMove=board.moveCount;
				String move="P "+file+rank+" "+(capstone?"C":"")+(wall?"W":"");
				moveList.add(move.trim());
				saveBoardPosition();
				undoRequestedBy = null;//remove any requests on move
				sendMove(p, move.trim());

				checkRoadWin();
				checkOutOfPieces();
				checkOutOfSquares();
				whenGameEnd();

				return new Status(true);
			} else {
				return new Status("Square not empty", false);
			}
		}
		finally{
			gameLock.unlock();
		}
	}

	Status moveMove(Player p, char f1, int r1, char f2, int r2, int[] vals) {
		gameLock.lock();
		try{
			//alternate turns
			if(!turnOf(p))
				return new Status("Not your turn", false);

			//first moves should be place moves
			if(board.moveCount/2==0)
				return new Status("First move should be place", false);
			
			//moves should be horizontal or vertical
			if(f1!=f2 && r1!=r2)
				return new Status("Move should be in straight line", false);

			Board.Square startSq = board.getSquare(f1, r1);
			Board.Square endSq = board.getSquare(f2, r2);
			//bounds checking of squares
			if(startSq == null || endSq == null)
				return new Status("Out of bounds", false);

			//stack should be controlled by current player
			if(p != stackController(startSq))
				return new Status("You don't control stack", false);

			//carry size should be less than or equal to boardSize and stack size
			int carrySize=0;
			for(int v:vals)
				carrySize+=v;
			if(carrySize>board.boardSize || carrySize>startSq.size())
				return new Status("Invalid move", false);

			//length of vals should be one less than no. of squares involved
			int num = (f1==f2)?abs(r1-r2):abs(f1-f2);
			if(vals.length != num)
				return new Status("Invalid move.", false);
			//all other squares should be greater than 0
			for(int i=0;i<vals.length;i++)
				if(vals[i]<1)
					return new Status("Should place atleast one tile in rest of"
							+ " the squares", false);

			char top = startSq.topOfStack();
			boolean capstone = isCapstone(top);

			SquareIterator sqIt = new SquareIterator(this, f1, r1, f2, r2);
			//check if none of the intermediate places are walls or capstones
			for(Board.Square sqr=sqIt.next();sqr!=null;sqr=sqIt.next()){
				if(sqr==startSq)
					continue;
				char ttop = sqr.topOfStack();

				//can't stack over capstones
				if(isCapstone(ttop))
					return new Status("Can't stack over capstones", false);

				//can't stack over walls.. but wall in last square can be flattened
				if(isWall(ttop)) {
					if(sqr!=endSq)
						return new Status("Can't stack over walls", false);
					else {
						if(vals[vals.length-1]!=1 || !capstone){
							return new Status("Capstone should be on top to flatten"+ " walls", false);
						}
					}
				}
			}

			//do the actual moving of stack
			sqIt.reset();
			int count=-1;
			Stack<Character> moveStack = new Stack<>();

			for(Board.Square sqr=sqIt.next();sqr!=null;
					sqr=sqIt.next(),count++){
				assert(count<vals.length);

				//move to temporary stack
				if(sqr==startSq){
					for(int i=0;i<carrySize;i++)
						moveStack.push(sqr.pop());
				}
				//flatten
				else if(sqr == endSq && moveStack.size()==1 && isCapstone(moveStack.peek()) && isWall(sqr.topOfStack())){
					char ch = sqr.pop();
					sqr.add(isWhite(ch)?Character.toUpperCase(FLAT):FLAT);
					sqr.add(moveStack.pop());
					board.lastResetMove=board.moveCount+1;
				}
				//move elements
				else {
					for(int i=0;i<vals[count];i++){
						sqr.add(moveStack.pop());
					}
				}
			}
			// the logic is backwards which is why it's not whties turn but adding to whites move count
			if(isWhitesTurn()) {
				this.playerWhiteMoveCount++;
			} else {
				this.playerBlackMoveCount++;
			}
			updateTimeTurnChange();
			board.moveCount++;
			updateOutOfTime();
			String move = "M "+f1+r1+" "+f2+r2+" ";
			for(int val: vals)
				move+=val+" ";
			moveList.add(move.trim());
			saveBoardPosition();
			undoRequestedBy = null;//remove any requests on move
			sendMove(p, move.trim());

			checkRoadWin();
			checkOutOfSquares();
			checkStaleGame();
			whenGameEnd();

			return new Status(true);
		}
		finally{
			gameLock.unlock();
		}
	}

	private Player stackController(Board.Square sq) {
		return Character.isUpperCase(sq.topOfStack())?white:black;
	}

	boolean isCapstone(char c) {
		return Character.toLowerCase(c) == CAPSTONE;
	}

	boolean isWall(char c) {
		return Character.toLowerCase(c) == WALL;
	}

	boolean isFlat(char c) {
		return Character.toLowerCase(c) == FLAT;
	}

	boolean isBlack(char c) {
		return (c==FLAT)||(c==WALL)||(c==CAPSTONE);
	}

	boolean isWhite(char c) {
		return c!=0 && !isBlack(c);
	}

	static int abs(int x) {
		return x>0?x:-x;
	}

	String getPTN() {
		gameLock.lock();
		try{
			String ret="";
			for(int i=0;i<board.boardSize;i++){
				int xcnt=0;
				for(int j=0;j<board.boardSize;j++){
					Board.Square sq = board.squares[i][j];
					if(sq.isEmpty())
						xcnt=0;
					else {
						ret+="x"+xcnt;
						String cell="";
						for(int k=0;k<sq.size();k++){
							char ch = sq.get(k);
							cell+=isWhite(ch)?"1":"2";
							if(isWall(ch))
								cell+="S";
							else if(isCapstone(ch))
								cell+="C";
						}
						if(j!=board.boardSize-1)
							cell+=",";
					}
				}
				ret+="/";
			}
			ret+=" "+board.moveCount+" "+(isWhitesTurn()?"1":"2");
			return ret;
		}
		finally{
			gameLock.unlock();
		}
	}

	private void whenGameEnd() {
		if(gameState==gameS.NONE)
			return;
		String msg="";
		msg += gameStateString();

		if(!abandoned)
			msg = "Game#"+no+" Over "+msg;
		else {
			Player abandoningPlayer;
			if(gameState==gameS.WHITE)
				abandoningPlayer = black;
			else
				abandoningPlayer = white;

			msg = "Game#"+no+" Abandoned. "+abandoningPlayer.getName()+" quit";
		}
		
		white.removeGame();
		black.removeGame();
		if(!(abandoned && gameState==gameS.BLACK))
			white.send(msg);
		if(!(abandoned && gameState==gameS.WHITE))
			black.send(msg);
		sendToSpectators(msg);

		saveToDB();

		notifySubscribers(GameUpdate.gameEnded(this.toDto()));
	}

	private String moveListString() {
		StringBuilder sb = new StringBuilder();
		String prefix = "";
		for(String move: moveList) {
			sb.append(prefix);
			prefix = ",";
			sb.append(move);
		}
		return sb.toString();
	}

	private String gameStateString() {
		switch(gameState) {
			case DRAW: return "1/2-1/2";
			case WHITE_ROAD: return "R-0";
			case BLACK_ROAD: return "0-R";
			case WHITE_TILE: return "F-0";
			case BLACK_TILE: return "0-F";
			case WHITE: return "1-0";
			case BLACK: return "0-1";
			case ABORT: return "0-0";
			default: return "---";
		}
	}

	private void insertEmpty() {
		try {
			String sql = "INSERT INTO games (date, size, player_white, player_black, timertime, timerinc, notation, result, rating_white, rating_black, unrated, tournament, komi, pieces, capstones, rating_change_white, rating_change_black, extra_time_amount, extra_time_trigger) " +
				"VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
			PreparedStatement stmt = Database.gamesConnection.prepareStatement
				(sql, Statement.RETURN_GENERATED_KEYS);
			stmt.setLong(1, time);
			stmt.setInt(2, board.boardSize);
			stmt.setString(3, white.getName());
			stmt.setString(4, black.getName());
			stmt.setLong(5, originalTime/1000);
			stmt.setLong(6, incrementTime/1000);
			stmt.setString(7, "");
			stmt.setString(8, "0-0");
			stmt.setInt(9, white.getRating(time));
			stmt.setInt(10, black.getRating(time));
			stmt.setInt(11, unrated);
			
			stmt.setInt(12, tournament);
			stmt.setInt(13, komi);
			stmt.setInt(14, tileCount);
			stmt.setInt(15, capCount);
			stmt.setInt(16, -1000);
			stmt.setInt(17, -1000);
			stmt.setInt(18, timeAmount / 1000);
			stmt.setInt(19, triggerMove);
			stmt.executeUpdate();
			ResultSet inserted = stmt.getGeneratedKeys();
			if (inserted.next())
				no = inserted.getInt(1);
			stmt.close();
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	private void saveToDB() {
		try {
			String sql = "UPDATE games " +
				"SET notation=?, result=? " +
				"WHERE id=?";
			PreparedStatement stmt = Database.gamesConnection.prepareStatement(sql);
			stmt.setString(1, moveListString());
			stmt.setString(2, gameStateString());
			stmt.setInt(3, no);
			stmt.executeUpdate();
			stmt.close();
		} catch (SQLException ex) {
			Logger.getLogger(Player.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	private void sendMove(Player p, String move) {
		String msg="Game#"+no+" "+move;
		sendToOtherPlayer(p, msg);
		sendToSpectators(msg);
	}

	private void sendToOtherPlayer(Player p, String move) {
		if(white==p)
			black.sendWithoutLogging(move);
		else
			white.sendWithoutLogging(move);
	}

	private void sendToSpectators(final String msg) {
		for(Player p:spectators){
			p.sendWithoutLogging(msg);
		}
	}

	private void findWhoWon() {
		int blackCount=0, whiteCount=0;
		for(int i=0;i<board.boardSize;i++){
			for(int j=0;j<board.boardSize;j++){
				char ch = board.squares[i][j].topOfStack();
				if(ch!=0 && !isWall(ch) && !isCapstone(ch)){
					if(isWhite(ch))
						whiteCount++;
					else
						blackCount++;
				}
			}
		}
		whiteCount*=2;
		blackCount*=2;
		blackCount+=komi;
		if(whiteCount==blackCount)
			gameState = gameS.DRAW;
		else if(whiteCount>blackCount)
			gameState = gameS.WHITE_TILE;
		else
			gameState = gameS.BLACK_TILE;
	}

	private void checkOutOfSquares() {
		if(gameState!=gameS.NONE)
			return;
		for(int i=0;i<board.boardSize;i++){
			for(int j=0;j<board.boardSize;j++){
				if(board.squares[i][j].isEmpty())
					return;
			}
		}
		System.out.println("Out of squares");
		findWhoWon();
	}

	private void checkStaleGame(){
		if(gameState!=gameS.NONE)
			return;
		if(board.lastResetMove+50<=board.moveCount){
			gameState = gameS.DRAW;
		}
	}

	private void checkRoadWin() {
		boolean whiteWin=false, blackWin=false;
		class Graph {
			private int lf, rf, tr, br;
			private ArrayList<Board.Square> squares;
			int no;

			Graph(int i, int j) {
				no = i+j*board.boardSize;
				lf=rf=tr=br=1000;
				squares = new ArrayList<>();
			}
			boolean add(Board.Square sq) {
				if(lf==1000 || sq.file < lf) lf = sq.file;
				if(rf==1000 || sq.file > rf) rf = sq.file;
				if(br==1000 || sq.row-1 < br) br = sq.row-1;
				if(tr==1000 || sq.row-1 > tr) tr = sq.row-1;

				squares.add(sq);
				sq.graphNo = no;

				return (lf==0 && rf == board.boardSize-1) || (br==0 && tr==board.boardSize-1);
			}
			boolean merge(Graph g) {
				if(g==this)
					return false;
				boolean ret=false;
				for(Board.Square sq: g.squares) {
					ret |= add(sq);
					sq.graphNo = no;
				}
				g.no = no;
				return ret;
			}
			@Override
			public String toString() {
				StringBuilder sb=new StringBuilder();
				sb.append("(").append(no).append("::").append(lf+" "+rf+" "+br+" "+tr).append(")");
				sb.append("[");
				for(Board.Square sq: squares)
					sb.append(sq).append(" ");
				sb.append("]");
				return sb.toString();
			}
		}
		Graph graph[] = new Graph[board.boardSize*board.boardSize];
		for(int i=0;i<board.boardSize;i++)
			for(int j=0;j<board.boardSize;j++)
				graph[i+j*board.boardSize] = new Graph(i, j);

		for(int i=0;i<board.boardSize;i++){
			for(int j=0;j<board.boardSize;j++){
				Board.Square sq = board.getSquare((char)('A'+i), j+1);
				sq.graphNo = -1;

				char ch = sq.topOfStack();
				if(ch==0 || isWall(ch))
					continue;
				graph[i+j*board.boardSize].add(sq);

				boolean left=false;
				boolean over=false;

				Board.Square lsq = board.getSquare((char)('A'+i-1), j+1);
				if(lsq!=null){
					char lch = lsq.topOfStack();
					if(lch!=0 && isWhite(ch)==isWhite(lch) && !isWall(lch)){
						over = graph[lsq.graphNo].merge(graph[i+j*board.boardSize]);
						graph[i+j*board.boardSize] = graph[lsq.graphNo];
					}
				}
				if(over) {
					if(isWhite(ch))
						whiteWin=true;
					else
						blackWin=true;
				}

				Board.Square tsq = board.getSquare((char)('A'+i), j-1+1);
				if(tsq!=null){
					char tch = tsq.topOfStack();
					if(tch!=0 && isWhite(ch)==isWhite(tch) && !isWall(tch)){
						over = graph[tsq.graphNo].merge(graph[i+j*board.boardSize]);
						graph[i+j*board.boardSize] = graph[tsq.graphNo];
					}
				}
				if(over) {
					if(isWhite(ch))
						whiteWin=true;
					else
						blackWin=true;
				}
			}
		}
		if(whiteWin && blackWin){
			gameState = isWhitesTurn()?gameS.BLACK_ROAD:gameS.WHITE_ROAD;
		} else if (whiteWin) {
			gameState = gameS.WHITE_ROAD;
		} else if (blackWin) {
			gameState = gameS.BLACK_ROAD;
		}
	}

	private Player otherPlayer(Player p) {
		return (p==white)?black:white;
	}

	void checkPlayerQuit() {
		gameLock.lock();
		try{
			if(gameState!=gameS.NONE){
				return;
			}
			long curTime=System.nanoTime();
			long time=reconnectionTime;
			if(tournament==1){
				time=900;
			}
			time*=1000000000;
			Player losingPlayer=null;
			if((curTime-logOutWhite)>time && !white.isLoggedIn()){
				losingPlayer=white;
			}
			else if((curTime-logOutBlack)>time && !black.isLoggedIn()){
				losingPlayer=black;
			}
			if(losingPlayer!=null){
				Player winningPlayer = otherPlayer(losingPlayer);
				abandoned = true;


				gameState = (losingPlayer==white)?gameS.BLACK:gameS.WHITE;
				whenGameEnd();
				Game.removeGame(this);
				losingPlayer.removeGame();
				winningPlayer.removeGame();
			}
		}
		finally{
			gameLock.unlock();
		}
	}

	void playerDisconnected(Player p) {
		gameLock.lock();
		try{
			int time=reconnectionTime;
			if(tournament==1){
				time=900;
			}
			Player otherP = otherPlayer(p);
			otherP.send("Message "+p.getName()+" has disconnected. They have "+time+" seconds to reconnect");
			sendToSpectators("Message "+p.getName()+" has disconnected. They have "+time+" seconds to reconnect");
			if(p==white){
				logOutWhite=System.nanoTime();
			}
			else{
				logOutBlack=System.nanoTime();
			}
			timer.schedule(new TimerTask() {
				@Override
				public void run() {
					checkPlayerQuit();
				}}, time*1000+1000);
		}
		finally{
			gameLock.unlock();
		}
	}

	void playerRejoin(Player p) {
		gameLock.lock();
		try{
			Player otherP = otherPlayer(p);
			String m = "";
			if(p.client.protocolVersion < 2) {
				m += "Game Start " + no +" "+board.boardSize+" "+white.getName()+" vs "+black.getName() + " ";
				m += ((white==p)?"white":"black")+" ";
				m += (originalTime/1000) + " " + komi + " " + tileCount + " " + capCount + " " + triggerMove + " " + timeAmount / 1000;
			} else {
				m += "Game Start " + no + " " + white.getName() + " vs " + black.getName() + " ";
				m += ((white==p)?"white":"black") + " ";
				m += board.boardSize + " " + (originalTime/1000) + " " + incrementTime / 1000 + " " + komi + " " + tileCount + " " + capCount + " " + unrated + " " + tournament + " " + triggerMove + " " + timeAmount / 1000 + " ";
				if (this.isBotGame) {
					m += "1";
				} else {
					m += "0";
				}
			}
			p.send(m);

			sendMoveListTo(p);
			updateTime(p);
			p.send("Message Your game is resumed");

			otherP.send("Message "+p.getName()+" has reconnected.");
			sendToSpectators("Message "+p.getName()+" has reconnected.");
			if(p==white){
				logOutWhite+=1000000000000L;
			}
			else{
				logOutBlack+=1000000000000L;
			}
		}
		finally{
			gameLock.unlock();
		}
	}

	class SquareIterator {
		Game game;
		char f1, f2;
		int r1, r2;

		static final int EAST = 0;
		static final int WEST = 1;
		static final int NORTH = 2;
		static final int SOUTH = 3;

		int direction;
		int count=0;
		int total;

		SquareIterator(Game game, char f1, int r1, char f2, int r2) {
			this.game = game;
			this.f1 = f1;
			this.r1 = r1;
			this.f2 = f2;
			this.r2 = r2;

			assert(f1==f2 || r1 == r2);
			assert(boundsCheck(f1, r1) && boundsCheck(f2, r2));

			if(f1==f2)
				direction = (r2-r1)>0?NORTH:SOUTH;
			else
				direction = (f2-f1)>0?EAST:WEST;

			switch(direction) {
				case EAST: total = f2-f1+1; break;
				case WEST: total = f1-f2+1; break;
				case NORTH: total = r2-r1+1; break;
				case SOUTH: total = r1-r2+1; break;
			}
		}

		Board.Square next() {
			if(count >= total)
				return null;

			Board.Square ret = null;

			switch(direction) {
				case EAST: ret = game.board.getSquare((char)(f1+count), r1); break;
				case WEST: ret = game.board.getSquare((char)(f1-count), r1); break;
				case NORTH: ret = game.board.getSquare(f1, r1+count); break;
				case SOUTH: ret = game.board.getSquare(f1, r1-count); break;
			}
			count++;
			return ret;
		}
		void reset() {
			count = 0;
		}
	}

	@Override
	public void subscribe(Subscriber<? super GameUpdate> subscriber) {
		subscribers.add(subscriber);
	}
	
	protected void notifySubscribers(GameUpdate update) {
		for (var subscriber: subscribers) {
			subscriber.onNext(update);
		}
	}
}
